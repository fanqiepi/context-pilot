package io.github.fanqiepi.contextpilot.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.github.fanqiepi.contextpilot.retrieval.RetrievalResultResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class ChatPromptComposer {

    static final String PROMPT_VERSION = "rag-answer-system-v1";
    private final String systemText;

    public ChatPromptComposer() {
        try {
            systemText = new ClassPathResource("prompts/rag-answer-system-v1.txt")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load RAG prompt", exception);
        }
    }

    ChatPrompt compose(String question, List<RetrievalResultResponse> evidence) {
        StringBuilder userText = new StringBuilder("Evidence:\n");
        for (int index = 0; index < evidence.size(); index++) {
            RetrievalResultResponse item = evidence.get(index);
            userText.append("\n[").append(index + 1).append("] ")
                    .append(item.originalFilename());
            if (item.pageNumber() != null) {
                userText.append(", page ").append(item.pageNumber());
            }
            userText.append("\n<evidence>\n")
                    .append(item.content())
                    .append("\n</evidence>\n");
        }
        userText.append("\nQuestion:\n").append(question);
        return new ChatPrompt(systemText, userText.toString(), PROMPT_VERSION);
    }
}
