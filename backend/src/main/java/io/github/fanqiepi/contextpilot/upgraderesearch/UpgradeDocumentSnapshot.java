package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UpgradeDocumentSnapshot(UUID id, UUID knowledgeBaseId, String filename, String sha256,
        String profileId, OffsetDateTime indexedAt, String status, boolean vectorPresent) {
    public java.util.Map<String,Object> frozen() {
        return java.util.Map.of("id",id.toString(),"filename",filename,"sha256",sha256,
                "profileId",profileId,"indexedAt",indexedAt.toInstant().toString());
    }
}
