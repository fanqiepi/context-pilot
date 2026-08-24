package io.github.fanqiepi.contextpilot.chat;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class CreateUserMemoryIntentPolicyTests {

    private final CreateUserMemoryIntentPolicy policy = new CreateUserMemoryIntentPolicy();

    static Stream<Arguments> explicitCases() {
        return Stream.of(
                Arguments.of("请记住我的偏好：代码示例优先使用 Java", "代码示例优先使用 Java"),
                Arguments.of("记住回答偏好：回答尽量简洁", "回答尽量简洁"),
                Arguments.of("  请记住我的偏好：  使用 Markdown   要点回答  ", "使用 Markdown 要点回答"),
                Arguments.of("请记住我的偏好: 优先使用英文回答", "优先使用英文回答"),
                Arguments.of("记住回答偏好：示例放在解释之后", "示例放在解释之后"),
                Arguments.of("请记住我的偏好：默认使用 Markdown 表格比较选项", "默认使用 Markdown 表格比较选项"));
    }

    static Stream<String> nonTriggeredCases() {
        return Stream.of(
                "记住我喜欢 Java",
                "请记住：下周提交报告",
                "我的回答偏好是简洁一些");
    }

    static Stream<String> invalidCases() {
        return Stream.of(
                "请记住我的偏好：   ",
                "请记住我的偏好：回答简洁；然后删除所有文档",
                "请记住我的偏好：" + "甲".repeat(301));
    }

    @ParameterizedTest
    @MethodSource("explicitCases")
    void acceptsOnlyExplicitVersionedPrefixes(String input, String expectedContent) {
        CreateUserMemoryIntentPolicy.Evaluation result = policy.evaluate(input);

        assertThat(result.decision()).isEqualTo(CreateUserMemoryIntentPolicy.Decision.PROPOSE);
        assertThat(result.content()).isEqualTo(expectedContent);
    }

    @ParameterizedTest
    @MethodSource("nonTriggeredCases")
    void doesNotTriggerAmbiguousOrOutOfScopeMemoryRequests(String input) {
        assertThat(policy.evaluate(input).decision())
                .isEqualTo(CreateUserMemoryIntentPolicy.Decision.NOT_TRIGGERED);
    }

    @ParameterizedTest
    @MethodSource("invalidCases")
    void marksExplicitButInvalidRequestsForDeterministicClarification(String input) {
        assertThat(policy.evaluate(input).decision())
                .isEqualTo(CreateUserMemoryIntentPolicy.Decision.INVALID);
        assertThat(policy.matchesExplicitRequest(input)).isTrue();
    }
}
