package io.github.fanqiepi.contextpilot.researchreport;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ResearchReport(UUID id,UUID ownerId,UUID knowledgeBaseId,UUID runId,UUID sourceActionId,
        String title,String body,String draftHash,String sources,String gaps,OffsetDateTime createdAt,int deleted) { }
