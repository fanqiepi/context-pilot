package io.github.fanqiepi.contextpilot.chat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import io.github.fanqiepi.contextpilot.model.ChatModelResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatPersistenceService {

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final MessageCitationMapper citationMapper;
    private final ModelCallMapper modelCallMapper;

    public ChatPersistenceService(
            ConversationMapper conversationMapper,
            ChatMessageMapper chatMessageMapper,
            MessageCitationMapper citationMapper,
            ModelCallMapper modelCallMapper) {
        this.conversationMapper = conversationMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.citationMapper = citationMapper;
        this.modelCallMapper = modelCallMapper;
    }

    @Transactional
    public PendingChatExchange begin(
            UUID requestedConversationId,
            UUID knowledgeBaseId,
            String question,
            String traceId) {
        OffsetDateTime now = now();
        ConversationEntity conversation = requestedConversationId == null
                ? createConversation(knowledgeBaseId, question, now)
                : requireConversation(requestedConversationId, knowledgeBaseId);

        ChatMessageEntity userMessage = message(
                conversation.getId(), ChatMessageRole.USER, question,
                ChatMessageStatus.COMPLETED, traceId, now);
        ChatMessageEntity assistantMessage = message(
                conversation.getId(), ChatMessageRole.ASSISTANT, "",
                ChatMessageStatus.PENDING, traceId, now);
        chatMessageMapper.insert(userMessage);
        chatMessageMapper.insert(assistantMessage);

        conversation.setUpdatedAt(now);
        conversationMapper.updateById(conversation);
        return new PendingChatExchange(
                conversation.getId(), userMessage.getId(), assistantMessage.getId());
    }

    @Transactional(readOnly = true)
    public void validateConversation(UUID requestedConversationId, UUID knowledgeBaseId) {
        if (requestedConversationId != null) {
            requireConversation(requestedConversationId, knowledgeBaseId);
        }
    }

    @Transactional
    public UUID beginModelCall(
            UUID assistantMessageId,
            String provider,
            String model,
            String promptVersion,
            String traceId) {
        OffsetDateTime now = now();
        ModelCallEntity call = new ModelCallEntity();
        call.setId(UUID.randomUUID());
        call.setMessageId(assistantMessageId);
        call.setProvider(provider);
        call.setModel(model);
        call.setPromptVersion(promptVersion);
        call.setStatus(ModelCallStatus.STARTED);
        call.setTraceId(traceId);
        call.setCreatedAt(now);
        call.setUpdatedAt(now);
        modelCallMapper.insert(call);
        return call.getId();
    }

    @Transactional
    public void completeWithoutModel(UUID assistantMessageId, String answer) {
        completeMessage(assistantMessageId, answer, ChatMessageStatus.COMPLETED, null);
    }

    @Transactional
    public void completeSuccess(
            UUID assistantMessageId,
            UUID modelCallId,
            String answer,
            List<ChatCitationResponse> citations,
            ChatModelResult result,
            long latencyMs) {
        OffsetDateTime now = now();
        completeMessage(assistantMessageId, answer, ChatMessageStatus.COMPLETED, null);
        for (ChatCitationResponse citation : citations) {
            MessageCitationEntity entity = new MessageCitationEntity();
            entity.setId(UUID.randomUUID());
            entity.setMessageId(assistantMessageId);
            entity.setDocumentId(citation.documentId());
            entity.setChunkId(citation.chunkId());
            entity.setOriginalFilename(citation.originalFilename());
            entity.setChunkIndex(citation.chunkIndex());
            entity.setPageNumber(citation.pageNumber());
            entity.setRankIndex(citation.rank());
            entity.setScore(citation.score());
            entity.setExcerpt(citation.excerpt());
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            citationMapper.insert(entity);
        }
        ModelCallEntity call = requireModelCall(modelCallId);
        call.setModel(result.model());
        call.setStatus(ModelCallStatus.SUCCEEDED);
        call.setPromptTokens(result.promptTokens());
        call.setCompletionTokens(result.completionTokens());
        call.setTotalTokens(result.totalTokens());
        call.setLatencyMs(latencyMs);
        call.setUpdatedAt(now);
        modelCallMapper.updateById(call);
    }

    @Transactional
    public void completeFailure(UUID assistantMessageId, UUID modelCallId, long latencyMs) {
        String summary = "Chat model call failed";
        completeMessage(assistantMessageId, "", ChatMessageStatus.FAILED, summary);
        ModelCallEntity call = requireModelCall(modelCallId);
        call.setStatus(ModelCallStatus.FAILED);
        call.setLatencyMs(latencyMs);
        call.setErrorSummary(summary);
        call.setUpdatedAt(now());
        modelCallMapper.updateById(call);
    }

    private ConversationEntity createConversation(
            UUID knowledgeBaseId,
            String question,
            OffsetDateTime now) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        conversation.setKnowledgeBaseId(knowledgeBaseId);
        conversation.setTitle(title(question));
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);
        return conversation;
    }

    private ConversationEntity requireConversation(UUID conversationId, UUID knowledgeBaseId) {
        ConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new ResourceNotFoundException(
                    "CONVERSATION_NOT_FOUND",
                    "Conversation " + conversationId + " was not found");
        }
        if (!conversation.getKnowledgeBaseId().equals(knowledgeBaseId)) {
            throw new BadRequestException(
                    "CONVERSATION_KNOWLEDGE_BASE_MISMATCH",
                    "Conversation does not belong to the selected knowledge base");
        }
        return conversation;
    }

    private ChatMessageEntity message(
            UUID conversationId,
            ChatMessageRole role,
            String content,
            ChatMessageStatus status,
            String traceId,
            OffsetDateTime now) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(UUID.randomUUID());
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setStatus(status);
        message.setTraceId(traceId);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        return message;
    }

    private void completeMessage(
            UUID messageId,
            String content,
            ChatMessageStatus status,
            String errorSummary) {
        ChatMessageEntity message = chatMessageMapper.selectById(messageId);
        if (message == null) {
            throw new IllegalStateException("Pending assistant message was not found");
        }
        message.setContent(content);
        message.setStatus(status);
        message.setErrorSummary(errorSummary);
        message.setUpdatedAt(now());
        chatMessageMapper.updateById(message);
    }

    private ModelCallEntity requireModelCall(UUID modelCallId) {
        ModelCallEntity call = modelCallMapper.selectById(modelCallId);
        if (call == null) {
            throw new IllegalStateException("Model call record was not found");
        }
        return call;
    }

    private String title(String question) {
        String normalized = question.strip().replaceAll("\\s+", " ");
        return normalized.length() <= 100 ? normalized : normalized.substring(0, 100);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
