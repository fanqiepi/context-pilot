package io.github.fanqiepi.contextpilot.readingnote;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Private persistence row; content is immutable after creation. */
public class ReadingNoteProposal {
    public UUID id,ownerId,bookId,versionId,taskId,clientRequestId,supersedesId,resultNoteId,traceId;
    public String requestHash,kind,title,body,citationsJson,contentHash,status,errorCode;
    public int requiredBoundary,deleted;
    public long scopeEpoch,revision;
    public boolean userEdited;
    public OffsetDateTime expiresAt,createdAt;
}
