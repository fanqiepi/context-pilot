package io.github.fanqiepi.contextpilot.chat;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.memory.UserMemoryContent;
import org.springframework.stereotype.Component;

@Component
class CreateUserMemoryIntentPolicy {

    private static final Pattern EXPLICIT_PREFIX = Pattern.compile(
            "^(?:请记住我的偏好|记住回答偏好)\\s*[:：]\\s*(?<content>.*)$",
            Pattern.DOTALL | Pattern.UNICODE_CASE);
    private static final Pattern ADDITIONAL_EXECUTION_INSTRUCTION = Pattern.compile(
            "(?:[;；]|[，,])\\s*(?:然后|并且|再|同时|接着)\\s*"
                    + "(?:删除|创建|新建|执行|运行|修改|上传|重试|重建|调用|发送)");

    Evaluation evaluate(String question) {
        if (question == null || question.isBlank()) {
            return new Evaluation(Decision.NOT_TRIGGERED, null);
        }
        String normalizedInput = Normalizer.normalize(question, Normalizer.Form.NFKC).strip();
        Matcher matcher = EXPLICIT_PREFIX.matcher(normalizedInput);
        if (!matcher.matches()) {
            return new Evaluation(Decision.NOT_TRIGGERED, null);
        }
        String content;
        try {
            content = UserMemoryContent.normalize(matcher.group("content"));
        } catch (BadRequestException exception) {
            return new Evaluation(Decision.INVALID, null);
        }
        if (ADDITIONAL_EXECUTION_INSTRUCTION.matcher(content).find()) {
            return new Evaluation(Decision.INVALID, null);
        }
        return new Evaluation(Decision.PROPOSE, content);
    }

    boolean matchesExplicitRequest(String question) {
        return evaluate(question).decision() != Decision.NOT_TRIGGERED;
    }

    enum Decision {
        PROPOSE,
        INVALID,
        NOT_TRIGGERED
    }

    record Evaluation(Decision decision, String content) {
    }
}
