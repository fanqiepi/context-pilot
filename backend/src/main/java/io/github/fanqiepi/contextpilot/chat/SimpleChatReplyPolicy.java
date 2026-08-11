package io.github.fanqiepi.contextpilot.chat;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
class SimpleChatReplyPolicy {

    static final String IDENTITY_REPLY =
            "我是 ContextPilot，一个基于所选知识库回答问题的资料助手。"
                    + "我可以帮助你检索文档、总结内容，并提供可核对的引用。";
    static final String GREETING_REPLY =
            "你好，我是 ContextPilot。你可以选择一个知识库，向我询问其中的内容。";
    static final String CAPABILITY_REPLY =
            "我可以基于当前选择的知识库回答问题、总结资料并提供引用。"
                    + "知识库之外的事实性问题我不会直接回答。";
    static final String THANKS_REPLY =
            "不客气。如果还想了解知识库中的内容，可以继续提问。";
    static final String FAREWELL_REPLY =
            "再见，欢迎随时回来继续查看知识库。";

    private static final Set<String> IDENTITY_QUESTIONS = Set.of(
            "你是谁",
            "你是谁啊",
            "你叫什么",
            "你叫什么名字",
            "请问你是谁",
            "介绍一下你自己",
            "请介绍一下你自己",
            "自我介绍",
            "你能介绍一下自己吗",
            "whoareyou",
            "whatareyou",
            "whatisyourname",
            "introduceyourself");
    private static final Set<String> GREETINGS = Set.of(
            "你好",
            "您好",
            "嗨",
            "哈喽",
            "在吗",
            "hello",
            "hi",
            "hey",
            "早上好",
            "下午好",
            "晚上好");
    private static final Set<String> CAPABILITY_QUESTIONS = Set.of(
            "你能做什么",
            "你会做什么",
            "你有什么功能",
            "你的功能是什么",
            "怎么使用你",
            "如何使用你",
            "whatcanyoudo",
            "howcaniuseyou");
    private static final Set<String> THANKS = Set.of(
            "谢谢",
            "谢谢你",
            "感谢",
            "感谢你",
            "多谢",
            "thanks",
            "thankyou");
    private static final Set<String> FAREWELLS = Set.of(
            "再见",
            "拜拜",
            "下次见",
            "bye",
            "goodbye",
            "seeyou");

    Optional<String> replyTo(String question) {
        String normalized = normalize(question);
        if (IDENTITY_QUESTIONS.contains(normalized)) {
            return Optional.of(IDENTITY_REPLY);
        }
        if (GREETINGS.contains(normalized)) {
            return Optional.of(GREETING_REPLY);
        }
        if (CAPABILITY_QUESTIONS.contains(normalized)) {
            return Optional.of(CAPABILITY_REPLY);
        }
        if (THANKS.contains(normalized)) {
            return Optional.of(THANKS_REPLY);
        }
        if (FAREWELLS.contains(normalized)) {
            return Optional.of(FAREWELL_REPLY);
        }
        return Optional.empty();
    }

    private String normalize(String question) {
        if (question == null) {
            return "";
        }
        return question
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
}
