package io.github.fanqiepi.contextpilot.readingnote;

import java.time.OffsetDateTime;
import java.util.UUID;
import com.baomidou.mybatisplus.annotation.*;

@TableName("reading_bookmark")
public class ReadingBookmark {
    @TableId(type=IdType.INPUT) public UUID id;
    public UUID ownerId;
    public UUID bookId;
    public UUID versionId;
    public UUID clientRequestId;
    public String requestHash;
    public UUID paragraphId;
    public int positionOffset;
    public String label;
    public int requiredBoundary;
    public long revision;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    @TableLogic public int deleted;
}
