package io.github.fanqiepi.contextpilot.feedback;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.fanqiepi.contextpilot.chat.ChatMessageEntity;
import io.github.fanqiepi.contextpilot.chat.ChatMessageMapper;
import io.github.fanqiepi.contextpilot.chat.ChatMessageRole;
import io.github.fanqiepi.contextpilot.chat.ChatMessageStatus;
import io.github.fanqiepi.contextpilot.chat.ConversationEntity;
import io.github.fanqiepi.contextpilot.chat.ConversationMapper;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.InternalServiceException;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnswerFeedbackService {

    private final AnswerFeedbackMapper answerFeedbackMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ConversationMapper conversationMapper;

    public AnswerFeedbackService(
            AnswerFeedbackMapper answerFeedbackMapper,
            ChatMessageMapper chatMessageMapper,
            ConversationMapper conversationMapper) {
        this.answerFeedbackMapper = answerFeedbackMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.conversationMapper = conversationMapper;
    }

    @Transactional
    public AnswerFeedbackResponse markHelpful(UUID messageId) {
        FeedbackContext context = requireFeedbackContext(messageId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AnswerFeedbackEntity feedback = new AnswerFeedbackEntity();
        feedback.setId(UUID.randomUUID());
        feedback.setMessageId(messageId);
        feedback.setCreatedAt(now);
        feedback.setUpdatedAt(now);

        AnswerFeedbackEntity stored;
        try {
            if (answerFeedbackMapper.markHelpful(feedback) == 0) {
                throw saveFailed(messageId, null);
            }
            stored = answerFeedbackMapper.selectOne(
                    Wrappers.<AnswerFeedbackEntity>lambdaQuery()
                            .eq(AnswerFeedbackEntity::getMessageId, messageId));
        } catch (DataAccessException exception) {
            throw saveFailed(messageId, exception);
        }
        if (stored == null) {
            throw saveFailed(messageId, null);
        }
        return AnswerFeedbackResponse.from(
                stored,
                context.conversation().getKnowledgeBaseId(),
                context.message().getTraceId());
    }

    @Transactional
    public void removeHelpful(UUID messageId) {
        requireFeedbackContext(messageId);
        answerFeedbackMapper.delete(
                Wrappers.<AnswerFeedbackEntity>lambdaQuery()
                        .eq(AnswerFeedbackEntity::getMessageId, messageId));
    }

    private FeedbackContext requireFeedbackContext(UUID messageId) {
        ChatMessageEntity message = chatMessageMapper.selectById(messageId);
        if (message == null) {
            throw notFound(messageId);
        }
        if (message.getRole() != ChatMessageRole.ASSISTANT
                || message.getStatus() != ChatMessageStatus.COMPLETED) {
            throw new ConflictException(
                    "MESSAGE_FEEDBACK_NOT_ALLOWED",
                    "Only completed assistant messages can be marked as helpful");
        }
        ConversationEntity conversation = conversationMapper.selectById(message.getConversationId());
        if (conversation == null) {
            throw notFound(messageId);
        }
        return new FeedbackContext(message, conversation);
    }

    private ResourceNotFoundException notFound(UUID messageId) {
        return new ResourceNotFoundException(
                "MESSAGE_NOT_FOUND",
                "Message " + messageId + " was not found");
    }

    private InternalServiceException saveFailed(UUID messageId, Throwable cause) {
        return new InternalServiceException(
                "ANSWER_FEEDBACK_SAVE_FAILED",
                "Feedback for message " + messageId + " could not be saved",
                cause == null ? new IllegalStateException("Feedback upsert did not return an active record") : cause);
    }

    private record FeedbackContext(ChatMessageEntity message, ConversationEntity conversation) {
    }
}
