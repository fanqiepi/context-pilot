package io.github.fanqiepi.contextpilot.chat;

import org.springframework.stereotype.Component;

@Component
class CapabilityRouter {

    private final SimpleChatReplyPolicy simpleChatReplyPolicy;
    private final CreateKnowledgeBaseIntentPolicy createKnowledgeBaseIntentPolicy;
    private final KnowledgeBaseHealthIntentPolicy knowledgeBaseHealthIntentPolicy;

    CapabilityRouter(
            SimpleChatReplyPolicy simpleChatReplyPolicy,
            CreateKnowledgeBaseIntentPolicy createKnowledgeBaseIntentPolicy,
            KnowledgeBaseHealthIntentPolicy knowledgeBaseHealthIntentPolicy) {
        this.simpleChatReplyPolicy = simpleChatReplyPolicy;
        this.createKnowledgeBaseIntentPolicy = createKnowledgeBaseIntentPolicy;
        this.knowledgeBaseHealthIntentPolicy = knowledgeBaseHealthIntentPolicy;
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
        if (knowledgeBaseHealthIntentPolicy.matches(question)) {
            return CapabilityRoute.matched(
                    CapabilityId.KNOWLEDGE_QA,
                    "v2",
                    CapabilityMatchReason.EXPLICIT_KNOWLEDGE_BASE_HEALTH,
                    traceId);
        }
        return CapabilityRoute.matched(
                CapabilityId.KNOWLEDGE_QA,
                CapabilityMatchReason.DEFAULT_KNOWLEDGE_QA,
                traceId);
    }
}
