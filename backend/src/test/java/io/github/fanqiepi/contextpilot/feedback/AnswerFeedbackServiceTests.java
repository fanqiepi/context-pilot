package io.github.fanqiepi.contextpilot.feedback;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.fanqiepi.contextpilot.chat.ChatMessageEntity;
import io.github.fanqiepi.contextpilot.chat.ChatMessageMapper;
import io.github.fanqiepi.contextpilot.chat.ChatMessageRole;
import io.github.fanqiepi.contextpilot.chat.ChatMessageStatus;
import io.github.fanqiepi.contextpilot.chat.ConversationEntity;
import io.github.fanqiepi.contextpilot.chat.ConversationMapper;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnswerFeedbackServiceTests {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        TableInfoHelper.initTableInfo(assistant, AnswerFeedbackEntity.class);
    }

    @Mock
    private AnswerFeedbackMapper answerFeedbackMapper;
    @Mock
    private ChatMessageMapper chatMessageMapper;
    @Mock
    private ConversationMapper conversationMapper;

    private AnswerFeedbackService service;

    @BeforeEach
    void setUp() {
        service = new AnswerFeedbackService(
                answerFeedbackMapper,
                chatMessageMapper,
                conversationMapper);
    }

    @Test
    void marksCompletedAssistantMessageHelpfulAndReturnsTraceContext() {
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        ChatMessageEntity message = message(
                messageId, conversationId, ChatMessageRole.ASSISTANT, ChatMessageStatus.COMPLETED);
        when(chatMessageMapper.selectById(messageId)).thenReturn(message);
        when(conversationMapper.selectById(conversationId))
                .thenReturn(conversation(conversationId, knowledgeBaseId));
        when(answerFeedbackMapper.markHelpful(any())).thenReturn(1);
        when(answerFeedbackMapper.selectOne(any())).thenAnswer(invocation -> {
            AnswerFeedbackEntity stored = new AnswerFeedbackEntity();
            stored.setId(UUID.randomUUID());
            stored.setMessageId(messageId);
            stored.setCreatedAt(OffsetDateTime.parse("2026-08-12T08:00:00Z"));
            stored.setUpdatedAt(stored.getCreatedAt());
            return stored;
        });

        AnswerFeedbackResponse response = service.markHelpful(messageId);

        ArgumentCaptor<AnswerFeedbackEntity> feedbackCaptor = ArgumentCaptor.forClass(AnswerFeedbackEntity.class);
        verify(answerFeedbackMapper).markHelpful(feedbackCaptor.capture());
        assertThat(feedbackCaptor.getValue().getMessageId()).isEqualTo(messageId);
        assertThat(response.messageId()).isEqualTo(messageId);
        assertThat(response.knowledgeBaseId()).isEqualTo(knowledgeBaseId);
        assertThat(response.traceId()).isEqualTo("trace-feedback");
        assertThat(response.helpful()).isTrue();
    }

    @Test
    void removesHelpfulMarkIdempotently() {
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(chatMessageMapper.selectById(messageId)).thenReturn(message(
                messageId, conversationId, ChatMessageRole.ASSISTANT, ChatMessageStatus.COMPLETED));
        when(conversationMapper.selectById(conversationId))
                .thenReturn(conversation(conversationId, UUID.randomUUID()));
        when(answerFeedbackMapper.delete(any())).thenReturn(0);

        service.removeHelpful(messageId);

        verify(answerFeedbackMapper).delete(any());
    }

    @Test
    void rejectsUserMessage() {
        UUID messageId = UUID.randomUUID();
        when(chatMessageMapper.selectById(messageId)).thenReturn(message(
                messageId, UUID.randomUUID(), ChatMessageRole.USER, ChatMessageStatus.COMPLETED));

        assertThatThrownBy(() -> service.markHelpful(messageId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("completed assistant messages");

        verify(conversationMapper, never()).selectById(any());
        verify(answerFeedbackMapper, never()).markHelpful(any());
    }

    @Test
    void rejectsPendingAssistantMessage() {
        UUID messageId = UUID.randomUUID();
        when(chatMessageMapper.selectById(messageId)).thenReturn(message(
                messageId, UUID.randomUUID(), ChatMessageRole.ASSISTANT, ChatMessageStatus.PENDING));

        assertThatThrownBy(() -> service.markHelpful(messageId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("completed assistant messages");

        verify(answerFeedbackMapper, never()).markHelpful(any());
    }

    @Test
    void rejectsMissingMessage() {
        UUID messageId = UUID.randomUUID();
        when(chatMessageMapper.selectById(messageId)).thenReturn(null);

        assertThatThrownBy(() -> service.markHelpful(messageId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(messageId.toString());

        verify(answerFeedbackMapper, never()).markHelpful(any());
    }

    private ChatMessageEntity message(
            UUID id,
            UUID conversationId,
            ChatMessageRole role,
            ChatMessageStatus status) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setId(id);
        entity.setConversationId(conversationId);
        entity.setRole(role);
        entity.setStatus(status);
        entity.setTraceId("trace-feedback");
        return entity;
    }

    private ConversationEntity conversation(UUID id, UUID knowledgeBaseId) {
        ConversationEntity entity = new ConversationEntity();
        entity.setId(id);
        entity.setKnowledgeBaseId(knowledgeBaseId);
        return entity;
    }
}
