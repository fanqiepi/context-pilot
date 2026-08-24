package io.github.fanqiepi.contextpilot.memory;

import java.util.UUID;

public class MessageMemoryContextHistoryRow {
    private UUID assistantMessageId;
    private UUID memoryId;
    private Integer revisionNumber;
    private String content;
    private Integer contextCharacterCount;
    private Boolean contextTruncated;

    public UUID getAssistantMessageId() { return assistantMessageId; }
    public void setAssistantMessageId(UUID assistantMessageId) { this.assistantMessageId = assistantMessageId; }
    public UUID getMemoryId() { return memoryId; }
    public void setMemoryId(UUID memoryId) { this.memoryId = memoryId; }
    public Integer getRevisionNumber() { return revisionNumber; }
    public void setRevisionNumber(Integer revisionNumber) { this.revisionNumber = revisionNumber; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getContextCharacterCount() { return contextCharacterCount; }
    public void setContextCharacterCount(Integer contextCharacterCount) { this.contextCharacterCount = contextCharacterCount; }
    public Boolean getContextTruncated() { return contextTruncated; }
    public void setContextTruncated(Boolean contextTruncated) { this.contextTruncated = contextTruncated; }
}
