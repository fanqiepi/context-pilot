package io.github.fanqiepi.contextpilot.chat;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationHistoryService {

    private static final Comparator<ChatMessageEntity> MESSAGE_ORDER = Comparator
            .comparing(ChatMessageEntity::getCreatedAt)
            .thenComparingInt(message -> message.getRole() == ChatMessageRole.USER ? 0 : 1)
            .thenComparing(ChatMessageEntity::getId);

    private final KnowledgeBaseService knowledgeBaseService;
    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final MessageCitationMapper messageCitationMapper;

    public ConversationHistoryService(
            KnowledgeBaseService knowledgeBaseService,
            ConversationMapper conversationMapper,
            ChatMessageMapper chatMessageMapper,
            MessageCitationMapper messageCitationMapper) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.conversationMapper = conversationMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.messageCitationMapper = messageCitationMapper;
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryResponse> list(UUID knowledgeBaseId) {
        knowledgeBaseService.get(knowledgeBaseId);
        return conversationMapper.selectList(
                        Wrappers.<ConversationEntity>lambdaQuery()
                                .eq(ConversationEntity::getKnowledgeBaseId, knowledgeBaseId)
                                .orderByDesc(ConversationEntity::getUpdatedAt)
                                .orderByDesc(ConversationEntity::getId))
                .stream()
                .map(ConversationSummaryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ConversationMessageResponse> messages(UUID conversationId) {
        requireConversation(conversationId);
        List<ChatMessageEntity> messages = chatMessageMapper.selectList(
                        Wrappers.<ChatMessageEntity>lambdaQuery()
                                .eq(ChatMessageEntity::getConversationId, conversationId)
                                .orderByAsc(ChatMessageEntity::getCreatedAt)
                                .orderByAsc(ChatMessageEntity::getId))
                .stream()
                .sorted(MESSAGE_ORDER)
                .toList();
        if (messages.isEmpty()) {
            return List.of();
        }

        List<UUID> messageIds = messages.stream().map(ChatMessageEntity::getId).toList();
        Map<UUID, List<ChatCitationResponse>> citationsByMessage = messageCitationMapper.selectList(
                        Wrappers.<MessageCitationEntity>lambdaQuery()
                                .in(MessageCitationEntity::getMessageId, messageIds)
                                .orderByAsc(MessageCitationEntity::getMessageId)
                                .orderByAsc(MessageCitationEntity::getRankIndex))
                .stream()
                .collect(Collectors.groupingBy(
                        MessageCitationEntity::getMessageId,
                        Collectors.mapping(ChatCitationResponse::from, Collectors.toList())));

        return messages.stream()
                .map(message -> ConversationMessageResponse.from(
                        message,
                        citationsByMessage.getOrDefault(message.getId(), List.of())))
                .toList();
    }

    private ConversationEntity requireConversation(UUID conversationId) {
        ConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new ResourceNotFoundException(
                    "CONVERSATION_NOT_FOUND",
                    "Conversation " + conversationId + " was not found");
        }
        return conversation;
    }
}
