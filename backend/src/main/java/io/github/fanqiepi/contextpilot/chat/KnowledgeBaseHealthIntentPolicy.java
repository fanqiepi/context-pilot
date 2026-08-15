package io.github.fanqiepi.contextpilot.chat;

import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
class KnowledgeBaseHealthIntentPolicy {

    private static final Set<String> EXACT_REQUESTS = Set.of(
            "检查这个知识库有没有异常",
            "检查当前知识库有没有异常",
            "检查知识库有没有异常",
            "检查这个知识库是否有异常",
            "检查当前知识库是否有异常",
            "检查知识库是否有异常",
            "检查这个知识库的健康状态",
            "检查当前知识库的健康状态",
            "检查知识库健康状态",
            "检查一下这个知识库的健康状态",
            "检查一下当前知识库的健康状态",
            "这个知识库是否健康",
            "当前知识库是否健康",
            "知识库健康检查",
            "检查知识库健康",
            "checkthisknowledgebaseforissues",
            "checkthehealthofthisknowledgebase",
            "isthisknowledgebasehealthy",
            "knowledgebasehealthcheck");

    boolean matches(String input) {
        if (input == null) {
            return false;
        }
        String normalized = input.strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[。！？!?]+$", "")
                .replaceAll("\\s+", "");
        return EXACT_REQUESTS.contains(normalized);
    }
}
