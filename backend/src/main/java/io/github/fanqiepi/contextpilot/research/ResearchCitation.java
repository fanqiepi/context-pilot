package io.github.fanqiepi.contextpilot.research;

import java.util.UUID;

import io.github.fanqiepi.contextpilot.chat.ChatCitationResponse;

public record ResearchCitation(UUID researchEvidenceId, ChatCitationResponse citation) {
}
