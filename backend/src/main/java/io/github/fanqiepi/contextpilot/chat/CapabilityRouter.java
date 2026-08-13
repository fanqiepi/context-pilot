package io.github.fanqiepi.contextpilot.chat;

import org.springframework.stereotype.Component;

@Component
class CapabilityRouter {

    private final SimpleChatReplyPolicy simpleChatReplyPolicy;
    private final CreateKnowledgeBaseIntentPolicy createKnowledgeBaseIntentPolicy;

    CapabilityRouter(
            SimpleChatReplyPolicy simpleChatReplyPolicy,
            CreateKnowledgeBaseIntentPolicy createKnowledgeBaseIntentPolicy) {
        this.simpleChatReplyPolicy = simpleChatReplyPolicy;
        this.createKnowledgeBaseIntentPolicy = createKnowledgeBaseIntentPolicy;
    }

    CapabilityRoute route(String question, String traceId) {
        if (simpleChatReplyPolicy.replyTo(question).isPresent()) {
            return CapabilityRoute.matched(
                    CapabilityId.SIMPLE_CHAT,
                    CapabilityMatchReason.SIMPLE_INTERACTION_WHITELIST,
                    traceId);
        }
        if (createKnowledgeBaseIntentPolicy.matches(question)) {
            return CapabilityRoute.matched(
                    CapabilityId.BUSINESS_ACTION,
                    CapabilityMatchReason.EXPLICIT_CREATE_KNOWLEDGE_BASE,
                    traceId);
        }
        return CapabilityRoute.matched(
                CapabilityId.KNOWLEDGE_QA,
                CapabilityMatchReason.DEFAULT_KNOWLEDGE_QA,
                traceId);
    }
}
