package io.github.fanqiepi.contextpilot.memory;

import java.util.UUID;

public class UserMemoryContextCandidate {
    private UUID memoryId;
    private UUID revisionId;
    private Integer revisionNumber;
    private String content;

    public UUID getMemoryId() { return memoryId; }
    public void setMemoryId(UUID memoryId) { this.memoryId = memoryId; }
    public UUID getRevisionId() { return revisionId; }
    public void setRevisionId(UUID revisionId) { this.revisionId = revisionId; }
    public Integer getRevisionNumber() { return revisionNumber; }
    public void setRevisionNumber(Integer revisionNumber) { this.revisionNumber = revisionNumber; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
