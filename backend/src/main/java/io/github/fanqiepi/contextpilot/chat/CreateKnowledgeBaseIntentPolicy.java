package io.github.fanqiepi.contextpilot.chat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
class CreateKnowledgeBaseIntentPolicy {

    private static final String CHINESE_PREFIX =
            "^(?:(?:请|请你|请帮我|帮我|麻烦你?|我想|我要|我需要)\\s*)?"
                    + "(?:创建|新建|建立)\\s*(?:一个|一份)?\\s*(?:新的?)?\\s*";
    private static final String CHINESE_DESCRIPTION =
            "(?:\\s*[，,]\\s*(?:描述|说明)(?:为|是|[:：])\\s*"
                    + "(?<description>[^。！？!?]{1,1500}))?";
    private static final Pattern CHINESE_NAMED_BEFORE = Pattern.compile(
            CHINESE_PREFIX
                    + "(?:名为|叫作|叫做|叫)\\s*"
                    + "(?<name>[^，,。！？!?：:]{1,1500}?)\\s*的?\\s*知识库"
                    + CHINESE_DESCRIPTION + "[。！!]*$");
    private static final Pattern CHINESE_KNOWLEDGE_BASE_FIRST = Pattern.compile(
            CHINESE_PREFIX
                    + "知识库(?:\\s*(?:名为|叫作|叫做|叫|[:：])\\s*"
                    + "(?<name>[^，,。！？!?：:]{1,1500}))?"
                    + CHINESE_DESCRIPTION + "[。！!]*$");
    private static final Pattern ENGLISH_CREATE_REQUEST = Pattern.compile(
            "^(?:please\\s+)?(?:create|add|make|set\\s+up)\\s+"
                    + "(?:a\\s+)?(?:new\\s+)?knowledge\\s*base"
                    + "(?:\\s+(?:named|called)\\s+(?<name>[^,;.!?]{1,1500}?))?"
                    + "(?:\\s*[,;]\\s*(?:with\\s+)?(?:description|described\\s+as)\\s*[:=]?\\s*"
                    + "(?<description>[^.!?]{1,1500}))?[.!]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    boolean matches(String question) {
        return parse(question).isPresent();
    }

    Optional<CreateKnowledgeBaseIntent> parse(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        String normalized = question.strip().replaceAll("\\s+", " ");
        for (Pattern pattern : new Pattern[] {
                CHINESE_NAMED_BEFORE, CHINESE_KNOWLEDGE_BASE_FIRST, ENGLISH_CREATE_REQUEST
        }) {
            Matcher matcher = pattern.matcher(normalized);
            if (matcher.matches()) {
                return Optional.of(new CreateKnowledgeBaseIntent(
                        group(matcher, "name"), group(matcher, "description")));
            }
        }
        return Optional.empty();
    }

    private String group(Matcher matcher, String name) {
        String value = matcher.group(name);
        return value == null ? null : value.strip();
    }

    record CreateKnowledgeBaseIntent(String name, String description) {
    }
}
