package io.github.fanqiepi.contextpilot.chat;

import java.util.List;

import io.github.fanqiepi.contextpilot.action.ActionRequestResponse;
import io.github.fanqiepi.contextpilot.health.KnowledgeBaseHealthReportResponse;

record PreparedChat(
        CapabilityRoute route,
        PendingChatExchange exchange,
        ChatPrompt prompt,
        List<ChatCitationResponse> citations,
        String directAnswer,
        KnowledgeBaseHealthReportResponse healthReport,
        ActionRequestResponse actionRequest) {

    PreparedChat(
            CapabilityRoute route,
            PendingChatExchange exchange,
            ChatPrompt prompt,
            List<ChatCitationResponse> citations) {
        this(route, exchange, prompt, citations, null, null, null);
    }

    static PreparedChat direct(
            CapabilityRoute route,
            PendingChatExchange exchange,
            String answer) {
        return new PreparedChat(route, exchange, null, List.of(), answer, null, null);
    }

    static PreparedChat health(
            CapabilityRoute route,
            PendingChatExchange exchange,
            KnowledgeBaseHealthReportResponse healthReport) {
        return new PreparedChat(
                route, exchange, null, List.of(), healthReport.summary(), healthReport, null);
    }

    static PreparedChat action(
            CapabilityRoute route,
            PendingChatExchange exchange,
            String answer,
            ActionRequestResponse actionRequest) {
        return new PreparedChat(route, exchange, null, List.of(), answer, null, actionRequest);
    }

    boolean refused() {
        return prompt == null && directAnswer == null;
    }

    boolean hasDirectAnswer() {
        return directAnswer != null;
    }

    boolean actionRequired() {
        return actionRequest != null;
    }

    boolean healthReportReady() {
        return healthReport != null;
    }
}
