package io.github.fanqiepi.contextpilot.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRouterTests {

    private final CapabilityRouter router = new CapabilityRouter(
            new SimpleChatReplyPolicy(),
            new CreateKnowledgeBaseIntentPolicy());

    @Test
    void routesWhitelistedSimpleInteractionFirst() {
        CapabilityRoute route = router.route(" 你好！ ", "trace-simple");

        assertThat(route.capabilityId()).isEqualTo(CapabilityId.SIMPLE_CHAT);
        assertThat(route.capabilityVersion()).isEqualTo("v1");
        assertThat(route.matchReason())
                .isEqualTo(CapabilityMatchReason.SIMPLE_INTERACTION_WHITELIST);
        assertThat(route.traceId()).isEqualTo("trace-simple");
    }

    @Test
    void routesExplicitCreateKnowledgeBaseRequests() {
        assertThat(router.route("请创建一个名为 Java 学习的知识库", "trace-cn").capabilityId())
                .isEqualTo(CapabilityId.BUSINESS_ACTION);
        assertThat(router.route("Create a knowledge base called Java Notes", "trace-en").capabilityId())
                .isEqualTo(CapabilityId.BUSINESS_ACTION);
        assertThat(router.route("新建知识库：架构资料", "trace-short").matchReason())
                .isEqualTo(CapabilityMatchReason.EXPLICIT_CREATE_KNOWLEDGE_BASE);
    }

    @Test
    void parsesOptionalDescriptionFromStrictCreateSyntax() {
        CreateKnowledgeBaseIntentPolicy policy = new CreateKnowledgeBaseIntentPolicy();

        CreateKnowledgeBaseIntentPolicy.CreateKnowledgeBaseIntent intent = policy
                .parse("创建一个名为 Java 学习的知识库，描述为后端学习资料")
                .orElseThrow();

        assertThat(intent.name()).isEqualTo("Java 学习");
        assertThat(intent.description()).isEqualTo("后端学习资料");
    }

    @Test
    void safelyDefaultsKnowledgeQuestionsWithoutTreatingHowToQuestionsAsActions() {
        assertThat(router.route("项目使用了什么数据库？", "trace-qa").capabilityId())
                .isEqualTo(CapabilityId.KNOWLEDGE_QA);
        assertThat(router.route("如何创建知识库？", "trace-how-to").capabilityId())
                .isEqualTo(CapabilityId.KNOWLEDGE_QA);
        assertThat(router.route("Can I create a knowledge base?", "trace-question").matchReason())
                .isEqualTo(CapabilityMatchReason.DEFAULT_KNOWLEDGE_QA);
        assertThat(router.route("创建关于知识库的使用说明", "trace-document").capabilityId())
                .isEqualTo(CapabilityId.KNOWLEDGE_QA);
    }
}
