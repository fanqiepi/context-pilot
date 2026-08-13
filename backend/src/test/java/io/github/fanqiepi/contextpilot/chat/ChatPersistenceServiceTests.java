package io.github.fanqiepi.contextpilot.chat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatPersistenceServiceTests {

    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private ChatMessageMapper chatMessageMapper;
    @Mock
    private MessageCitationMapper citationMapper;
    @Mock
    private ModelCallMapper modelCallMapper;

    private ChatPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new ChatPersistenceService(
                conversationMapper,
                chatMessageMapper,
                citationMapper,
                modelCallMapper);
    }

    @Test
    void persistsCapabilityRouteOnBothMessages() {
        UUID knowledgeBaseId = UUID.randomUUID();
        CapabilityRoute route = CapabilityRoute.matched(
                CapabilityId.BUSINESS_ACTION,
                CapabilityMatchReason.EXPLICIT_CREATE_KNOWLEDGE_BASE,
                "trace-action");

        PendingChatExchange exchange = service.begin(
                null, knowledgeBaseId, "创建知识库：Java 学习", route);

        ArgumentCaptor<ChatMessageEntity> messageCaptor =
                ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatMessageMapper, org.mockito.Mockito.times(2)).insert(messageCaptor.capture());
        List<ChatMessageEntity> messages = messageCaptor.getAllValues();
        assertThat(messages).extracting(ChatMessageEntity::getId)
                .containsExactly(exchange.userMessageId(), exchange.assistantMessageId());
        assertThat(messages).allSatisfy(message -> {
            assertThat(message.getTraceId()).isEqualTo("trace-action");
            assertThat(message.getCapabilityId()).isEqualTo(CapabilityId.BUSINESS_ACTION);
            assertThat(message.getCapabilityVersion()).isEqualTo("v1");
            assertThat(message.getCapabilityMatchReason())
                    .isEqualTo(CapabilityMatchReason.EXPLICIT_CREATE_KNOWLEDGE_BASE);
        });
        verify(conversationMapper).insert(any(ConversationEntity.class));
    }
}
