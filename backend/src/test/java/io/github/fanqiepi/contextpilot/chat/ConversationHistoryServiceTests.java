package io.github.fanqiepi.contextpilot.chat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import io.github.fanqiepi.contextpilot.feedback.AnswerFeedbackEntity;
import io.github.fanqiepi.contextpilot.feedback.AnswerFeedbackMapper;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationHistoryServiceTests {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        TableInfoHelper.initTableInfo(assistant, ConversationEntity.class);
        TableInfoHelper.initTableInfo(assistant, ChatMessageEntity.class);
        TableInfoHelper.initTableInfo(assistant, MessageCitationEntity.class);
        TableInfoHelper.initTableInfo(assistant, AnswerFeedbackEntity.class);
    }

    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private ChatMessageMapper chatMessageMapper;
    @Mock
    private MessageCitationMapper messageCitationMapper;
    @Mock
    private AnswerFeedbackMapper answerFeedbackMapper;

    private ConversationHistoryService service;

    @BeforeEach
    void setUp() {
        service = new ConversationHistoryService(
                knowledgeBaseService,
                conversationMapper,
                chatMessageMapper,
                messageCitationMapper,
                answerFeedbackMapper);
    }

    @Test
    void listsConversationsForExistingKnowledgeBase() {
        UUID knowledgeBaseId = UUID.randomUUID();
        ConversationEntity latest = conversation(
                UUID.randomUUID(), knowledgeBaseId, "Latest", OffsetDateTime.parse("2026-08-10T08:00:00Z"));
        ConversationEntity earlier = conversation(
                UUID.randomUUID(), knowledgeBaseId, "Earlier", OffsetDateTime.parse("2026-08-09T08:00:00Z"));
        when(conversationMapper.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<ConversationEntity> wrapper = invocation.getArgument(0);
            assertThat(normalizedSql(wrapper.getSqlSegment()))
                    .contains("knowledge_base_id")
                    .contains("order by updated_at desc,id desc");
            assertThat(wrapper.getParamNameValuePairs()).containsValue(knowledgeBaseId);
            return List.of(latest, earlier);
        });

        List<ConversationSummaryResponse> result = service.list(knowledgeBaseId);

        verify(knowledgeBaseService).get(knowledgeBaseId);
        assertThat(result).extracting(ConversationSummaryResponse::id)
                .containsExactly(latest.getId(), earlier.getId());
        assertThat(result.getFirst().knowledgeBaseId()).isEqualTo(knowledgeBaseId);
        assertThat(result.getFirst().title()).isEqualTo("Latest");
    }

    @Test
    void returnsMessagesWithCitationsAssociatedByMessage() {
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        when(conversationMapper.selectById(conversationId)).thenReturn(conversation(
                conversationId, knowledgeBaseId, "Question", OffsetDateTime.parse("2026-08-10T08:00:00Z")));
        when(chatMessageMapper.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<ChatMessageEntity> wrapper = invocation.getArgument(0);
            assertThat(normalizedSql(wrapper.getSqlSegment()))
                    .contains("conversation_id")
                    .contains("order by created_at asc,id asc");
            assertThat(wrapper.getParamNameValuePairs()).containsValue(conversationId);
            return List.of(
                    message(
                            assistantMessageId,
                            conversationId,
                            ChatMessageRole.ASSISTANT,
                            "Answer [1].",
                            "trace-answer"),
                    message(userMessageId, conversationId, ChatMessageRole.USER, "Question", "trace-user"));
        });
        when(messageCitationMapper.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<MessageCitationEntity> wrapper = invocation.getArgument(0);
            assertThat(normalizedSql(wrapper.getSqlSegment()))
                    .contains("message_id in")
                    .contains("order by message_id asc,rank_index asc");
            assertThat(wrapper.getParamNameValuePairs())
                    .containsValue(userMessageId)
                    .containsValue(assistantMessageId);
            return List.of(
                    citation(assistantMessageId, 1, "first.txt"),
                    citation(assistantMessageId, 2, "second.md"));
        });
        when(answerFeedbackMapper.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<AnswerFeedbackEntity> wrapper = invocation.getArgument(0);
            assertThat(normalizedSql(wrapper.getSqlSegment())).contains("message_id in");
            assertThat(wrapper.getParamNameValuePairs())
                    .containsValue(userMessageId)
                    .containsValue(assistantMessageId);
            return List.of(feedback(assistantMessageId));
        });

        List<ConversationMessageResponse> result = service.messages(conversationId);

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().role()).isEqualTo(ChatMessageRole.USER);
        assertThat(result.getFirst().citations()).isEmpty();
        assertThat(result.getFirst().helpful()).isFalse();
        assertThat(result.getLast().content()).isEqualTo("Answer [1].");
        assertThat(result.getLast().traceId()).isEqualTo("trace-answer");
        assertThat(result.getLast().citations())
                .extracting(ChatCitationResponse::rank)
                .containsExactly(1, 2);
        assertThat(result.getLast().citations().getFirst().originalFilename()).isEqualTo("first.txt");
        assertThat(result.getLast().helpful()).isTrue();
    }

    @Test
    void returnsEmptyMessageListWithoutLoadingCitations() {
        UUID conversationId = UUID.randomUUID();
        when(conversationMapper.selectById(conversationId)).thenReturn(conversation(
                conversationId, UUID.randomUUID(), "Empty", OffsetDateTime.now(ZoneOffset.UTC)));
        when(chatMessageMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.messages(conversationId)).isEmpty();

        verify(messageCitationMapper, never()).selectList(any());
        verify(answerFeedbackMapper, never()).selectList(any());
    }

    @Test
    void rejectsMissingConversation() {
        UUID conversationId = UUID.randomUUID();
        when(conversationMapper.selectById(conversationId)).thenReturn(null);

        assertThatThrownBy(() -> service.messages(conversationId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(conversationId.toString());

        verify(chatMessageMapper, never()).selectList(any());
        verify(messageCitationMapper, never()).selectList(any());
        verify(answerFeedbackMapper, never()).selectList(any());
    }

    private ConversationEntity conversation(
            UUID id,
            UUID knowledgeBaseId,
            String title,
            OffsetDateTime updatedAt) {
        ConversationEntity entity = new ConversationEntity();
        entity.setId(id);
        entity.setKnowledgeBaseId(knowledgeBaseId);
        entity.setTitle(title);
        entity.setCreatedAt(updatedAt.minusMinutes(1));
        entity.setUpdatedAt(updatedAt);
        return entity;
    }

    private ChatMessageEntity message(
            UUID id,
            UUID conversationId,
            ChatMessageRole role,
            String content,
            String traceId) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setId(id);
        entity.setConversationId(conversationId);
        entity.setRole(role);
        entity.setContent(content);
        entity.setStatus(ChatMessageStatus.COMPLETED);
        entity.setTraceId(traceId);
        entity.setCreatedAt(OffsetDateTime.parse("2026-08-10T08:00:00Z"));
        entity.setUpdatedAt(OffsetDateTime.parse("2026-08-10T08:00:01Z"));
        return entity;
    }

    private MessageCitationEntity citation(UUID messageId, int rank, String filename) {
        MessageCitationEntity entity = new MessageCitationEntity();
        entity.setId(UUID.randomUUID());
        entity.setMessageId(messageId);
        entity.setDocumentId(UUID.randomUUID());
        entity.setChunkId("chunk-" + rank);
        entity.setOriginalFilename(filename);
        entity.setChunkIndex(rank - 1);
        entity.setRankIndex(rank);
        entity.setScore(0.9 - rank * 0.1);
        entity.setExcerpt("Excerpt " + rank);
        return entity;
    }

    private AnswerFeedbackEntity feedback(UUID messageId) {
        AnswerFeedbackEntity entity = new AnswerFeedbackEntity();
        entity.setId(UUID.randomUUID());
        entity.setMessageId(messageId);
        entity.setCreatedAt(OffsetDateTime.parse("2026-08-10T08:00:02Z"));
        entity.setUpdatedAt(OffsetDateTime.parse("2026-08-10T08:00:02Z"));
        return entity;
    }

    private String normalizedSql(String sql) {
        return sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
