package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record UpgradeEvidence(UUID id, UUID runId, String sourceId, String sourceType, UUID documentId,
        String chunkId, String profileId, String contentHash, Long releaseId, String releaseTag,
        String sourceUrl, OffsetDateTime publishedAt, OffsetDateTime fetchedAt, String excerpt,
        boolean truncated, boolean prerelease) {
    public Map<String,Object> toolData() {
        var value=new java.util.LinkedHashMap<String,Object>();
        value.put("sourceId",sourceId);value.put("sourceType",sourceType);value.put("untrusted",true);
        value.put("excerpt",excerpt);value.put("truncated",truncated);value.put("prerelease",prerelease);
        if(documentId!=null){value.put("documentId",documentId.toString());value.put("chunkId",chunkId);}
        if(sourceUrl!=null){value.put("url",sourceUrl);value.put("tag",releaseTag);value.put("publishedAt",publishedAt.toString());}
        value.put("fetchedAt",fetchedAt.toString());return value;
    }
}
