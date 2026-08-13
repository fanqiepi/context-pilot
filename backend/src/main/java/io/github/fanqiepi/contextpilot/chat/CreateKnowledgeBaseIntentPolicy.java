package io.github.fanqiepi.contextpilot.chat;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
class CreateKnowledgeBaseIntentPolicy {

    private static final Pattern CHINESE_CREATE_REQUEST = Pattern.compile(
            "^(?:(?:请|请你|请帮我|帮我|麻烦你?|我想|我要|我需要)\\s*)?"
                    + "(?:创建|新建|建立)\\s*"
                    + "(?:一个|一份)?\\s*(?:新的?)?\\s*"
                    + "(?:"
                    + "知识库(?:\\s*[:：]\\s*[^，。！？!?：:]{1,100})?"
                    + "|(?:名为|叫作|叫做|叫)\\s*[^，。！？!?：:]{1,100}\\s*的?\\s*知识库"
                    + ")[。！!]*$");
    private static final Pattern ENGLISH_CREATE_REQUEST = Pattern.compile(
            "^(?:please\\s+)?(?:create|add|make|set\\s+up)\\s+"
                    + "(?:a\\s+)?(?:new\\s+)?knowledge\\s*base"
                    + "(?:\\s+(?:named|called)\\s+[^?]{1,200})?[.!]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CHINESE_EXPLICIT_NAME = Pattern.compile(
            ".*(?:名为|叫做|叫|[:：])\\s*[^，。！？!?：:]{1,100}(?:的知识库|[。！!]*)$");
    private static final Pattern ENGLISH_EXPLICIT_NAME = Pattern.compile(
            ".*\\b(?:named|called)\\s+[^?]{1,100}[.!]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    boolean matches(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.strip().replaceAll("\\s+", " ");
        return CHINESE_CREATE_REQUEST.matcher(normalized).matches()
                || ENGLISH_CREATE_REQUEST.matcher(normalized).matches();
    }

    boolean hasExplicitName(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.strip().replaceAll("\\s+", " ");
        return CHINESE_EXPLICIT_NAME.matcher(normalized).matches()
                || ENGLISH_EXPLICIT_NAME.matcher(normalized).matches();
    }
}
