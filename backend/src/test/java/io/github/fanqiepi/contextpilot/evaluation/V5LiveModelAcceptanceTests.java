package io.github.fanqiepi.contextpilot.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fanqiepi.contextpilot.action.ActionRequestService;
import io.github.fanqiepi.contextpilot.action.ActionRequestStatus;
import io.github.fanqiepi.contextpilot.chat.*;
import io.github.fanqiepi.contextpilot.document.*;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseCreateRequest;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import io.github.fanqiepi.contextpilot.memory.MemoryMode;
import io.github.fanqiepi.contextpilot.memory.UserMemoryService;
import io.github.fanqiepi.contextpilot.research.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/** Real production services, PostgreSQL, DashScope retrieval and DeepSeek; explicitly opt-in. */
@SpringBootTest(properties = {
        "spring.ai.model.chat=deepseek", "spring.ai.model.embedding=openai",
        "spring.ai.vectorstore.type=pgvector", "contextpilot.document.processing.enabled=true",
        "contextpilot.storage.root=target/v5-live-uploads",
        "spring.ai.deepseek.chat.options.temperature=0.0",
        "spring.ai.retry.max-attempts=1"
})
@Testcontainers
@EnabledIfSystemProperty(named = "contextpilot.evaluation.v5-live", matches = "true")
class V5LiveModelAcceptanceTests {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName
            .parse("pgvector/pgvector:0.8.2-pg17-bookworm").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("context_pilot").withUsername("context_pilot")
            .withPassword("context_pilot_test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private KnowledgeBaseService knowledgeBases;
    @Autowired private DocumentService documents;
    @Autowired private ChatApplicationService chat;
    @Autowired private ResearchChatService research;
    @Autowired private ActionRequestService actions;
    @Autowired private UserMemoryService memories;
    @Autowired private ConversationHistoryService history;
    @Autowired private JdbcTemplate jdbc;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");

    @Test
    @Timeout(1800)
    void comparesNoMemoryAndConfirmedPreferencesAcrossBothProductionPaths() throws Exception {
        Path root = Path.of(System.getProperty("contextpilot.evals.root", "../evals"));
        Path output = Path.of("target/v5-live-acceptance-results.json");
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("datasetId", "v5-live-memory-v1");
        report.put("startedAt", OffsetDateTime.now().toString());
        report.put("retrieval", "REAL_DASHSCOPE_PGVECTOR");
        report.put("results", results);
        report.put("status", "RUNNING");
        Files.writeString(Path.of("target/v5-live-public-answer-review.md"),
                "# Public synthetic answer review\n", StandardCharsets.UTF_8);
        write(output, report);
        try {
            UUID kb = knowledgeBases.create(new KnowledgeBaseCreateRequest("V5 live acceptance", null)).id();
            List<UUID> documentIds = new ArrayList<>();
            for (String filename : List.of("atlas-platform.md", "borealis-platform.md")) {
                byte[] content = Files.readAllBytes(root.resolve("corpora/v4-document-comparison-v1/" + filename));
                documentIds.add(documents.upload(kb,
                        new MockMultipartFile("file", filename, "text/markdown", content)).id());
            }
            awaitDocuments(kb);
            JsonNode cases = JSON.readTree(root.resolve("datasets/v5-live-memory-v1.json").toFile()).get("cases");
            assertThat(cases.size()).isEqualTo(4);
            for (JsonNode testCase : cases) {
                ChatAnswerResponse proposal = chat.answer(new ChatRequest(null, kb,
                        "请记住我的偏好：" + testCase.get("preference").asText()), UUID.randomUUID().toString());
                assertThat(proposal.actionRequest()).isNotNull();
                assertThat(memories.list(null, null)).isEmpty();
                var confirmed = actions.confirm(proposal.actionRequest().id());
                assertThat(confirmed.status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
                UUID memoryId = confirmed.resultResourceId();
                try {
                    for (boolean comparison : List.of(false, true)) {
                        ChatAnswerResponse baseline = run(kb, documentIds, testCase, comparison, MemoryMode.IGNORE);
                        results.add(score(testCase, comparison, MemoryMode.IGNORE, baseline, documentIds, memoryId));
                        write(output, report);
                        ChatAnswerResponse candidate = run(kb, documentIds, testCase, comparison, MemoryMode.USE);
                        Map<String, Object> scored = score(testCase, comparison, MemoryMode.USE, candidate, documentIds, memoryId);
                        scored.put("planUnchanged", !comparison || plan(baseline).equals(plan(candidate)));
                        scored.put("newSession", !candidate.conversationId().equals(proposal.conversationId())
                                && !candidate.conversationId().equals(baseline.conversationId()));
                        results.add(scored);
                        write(output, report);
                    }
                } finally {
                    memories.delete(memoryId);
                }
            }
            assertThat(results).hasSize(16);
            for (Map<String, Object> result : results) {
                for (String gate : List.of("answered", "citationsValid", "memoryCorrect", "historyCorrect", "oneModelCall")) {
                    assertThat(result.get(gate)).as("%s %s %s", result.get("caseId"), result.get("path"), gate).isEqualTo(true);
                }
                if (result.get("mode").equals("USE")) {
                    assertThat(result.get("stylePassed")).as("%s %s style", result.get("caseId"), result.get("path")).isEqualTo(true);
                    assertThat(result.get("planUnchanged")).isEqualTo(true);
                    assertThat(result.get("newSession")).isEqualTo(true);
                }
            }
            report.put("status", "AUTOMATED_GATES_PASSED_MANUAL_REVIEW_REQUIRED");
        } catch (Exception | AssertionError failure) {
            report.put("status", "FAILED");
            report.put("errorType", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            report.put("finishedAt", OffsetDateTime.now().toString());
            write(output, report);
        }
    }

    private ChatAnswerResponse run(UUID kb, List<UUID> ids, JsonNode testCase, boolean comparison, MemoryMode mode) {
        ResearchRequest selection = comparison
                ? new ResearchRequest(UUID.randomUUID(), ResearchTaskType.DOCUMENT_COMPARISON, ids) : null;
        ChatRequest request = new ChatRequest(null, kb, testCase.get("question").asText(), selection, mode);
        String trace = UUID.randomUUID().toString();
        return comparison ? research.answer(request, trace) : chat.answer(request, trace);
    }

    private Map<String, Object> score(JsonNode testCase, boolean comparison, MemoryMode mode,
            ChatAnswerResponse answer, List<UUID> ids, UUID memoryId) throws Exception {
        Files.writeString(Path.of("target/v5-live-public-answer-review.md"),
                "\n## " + testCase.get("id").asText() + " / " + (comparison ? "V4" : "RAG")
                        + " / " + mode + "\n\n" + answer.answer() + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", testCase.get("id").asText());
        result.put("path", comparison ? "DOCUMENT_COMPARISON" : "RAG");
        result.put("mode", mode.name());
        result.put("traceId", answer.traceId());
        result.put("model", answer.model());
        result.put("usage", answer.usage());
        result.put("answerCharacters", answer.answer().length());
        result.put("answered", !answer.refused() && answer.model() != null
                && (!comparison || answer.answer().length() <= 1800));
        var matcher = CITATION.matcher(answer.answer());
        boolean citationsValid = !answer.citations().isEmpty() && matcher.find()
                && answer.citations().stream().allMatch(citation -> ids.contains(citation.documentId()));
        matcher.reset();
        while (matcher.find()) {
            String rank = matcher.group(1);
            citationsValid &= answer.citations().stream().anyMatch(citation -> Integer.toString(citation.rank()).equals(rank));
        }
        result.put("citationsValid", citationsValid);
        var context = answer.memoryContext();
        result.put("memoryCorrect", mode == MemoryMode.IGNORE ? context == null
                : context != null && context.count() == 1 && context.characterCount() <= 2000
                && context.items().getFirst().memoryId().equals(memoryId)
                && context.items().getFirst().revision() == 1);
        var restored = history.messages(answer.conversationId()).stream()
                .filter(message -> message.id().equals(answer.assistantMessageId())).findFirst().orElseThrow();
        result.put("historyCorrect", java.util.Objects.equals(context, restored.memoryContext()));
        result.put("oneModelCall", jdbc.queryForObject(
                "SELECT COUNT(*) FROM model_call WHERE message_id = ?", Integer.class,
                answer.assistantMessageId()) == 1);
        result.put("stylePassed", style(testCase.get("style").asText(), answer.answer()));
        return result;
    }

    private List<String> plan(ChatAnswerResponse answer) {
        return answer.researchRun().steps().stream()
                .map(step -> step.ordinal() + ":" + step.goal() + ":" + step.query() + ":" + step.documentIds()).toList();
    }

    private boolean style(String expected, String answer) {
        long han = answer.codePoints().filter(code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN).count();
        return switch (expected) {
            case "ENGLISH" -> han < 10 && Pattern.compile("[A-Za-z]{3,}").matcher(answer).results().count() >= 10;
            case "CHINESE" -> han >= 20;
            case "BULLETS" -> Pattern.compile("(?m)^\\s*[-*+]\\s+\\S").matcher(answer).results().count() >= 2;
            case "CONCISE" -> answer.length() <= 600;
            default -> throw new IllegalArgumentException("Unknown style predicate");
        };
    }

    private void awaitDocuments(UUID kb) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (System.nanoTime() < deadline) {
            var current = documents.list(kb);
            assertThat(current).noneMatch(document -> document.status() == DocumentStatus.FAILED);
            if (current.size() == 2 && current.stream().allMatch(document -> document.status() == DocumentStatus.SUCCEEDED)) return;
            Thread.sleep(250);
        }
        throw new IllegalStateException("Public evaluation document ingestion timed out");
    }

    private void write(Path output, Map<String, Object> report) throws Exception {
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }
}
