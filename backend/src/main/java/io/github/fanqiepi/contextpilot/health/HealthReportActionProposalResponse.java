package io.github.fanqiepi.contextpilot.health;

import io.github.fanqiepi.contextpilot.action.ActionRequestResponse;
import io.github.fanqiepi.contextpilot.chat.ConversationMessageResponse;

public record HealthReportActionProposalResponse(
        boolean reusedExistingProposal,
        ConversationMessageResponse userMessage,
        ConversationMessageResponse assistantMessage,
        ActionRequestResponse actionRequest) {
}
