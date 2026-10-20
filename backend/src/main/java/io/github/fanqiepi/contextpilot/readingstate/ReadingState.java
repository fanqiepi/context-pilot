package io.github.fanqiepi.contextpilot.readingstate;

import java.time.OffsetDateTime;
import java.util.UUID;
import com.baomidou.mybatisplus.annotation.*;

@TableName("reading_state")
public class ReadingState {
    @TableId(type=IdType.INPUT) public UUID id;
    public UUID ownerId;
    public UUID bookId;
    public UUID versionId;
    public UUID resumeParagraphId;
    public int resumeOffset;
    public long resumeRevision;
    public OffsetDateTime lastReadAt;
    public int boundaryOrdinal;
    public long scopeRevision;
    public long visibilityEpoch;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    @TableLogic public int deleted;
}
