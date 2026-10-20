package io.github.fanqiepi.contextpilot.readingnote;

import java.time.OffsetDateTime;
import java.util.UUID;
import com.baomidou.mybatisplus.annotation.*;

@TableName("reading_note")
public class ReadingNote {
    @TableId(type=IdType.INPUT) public UUID id;
    public UUID ownerId;
    public UUID bookId;
    public UUID versionId;
    public UUID clientRequestId;
    public String requestHash;
    public String kind;
    public String title;
    public String body;
    public UUID sourceTaskId;
    public UUID sourceProposalId;
    public String citationsJson="[]";
    public boolean userEdited;
    public UUID anchorParagraphId;
    public Integer anchorStart;
    public Integer anchorEnd;
    public int requiredBoundary;
    public long revision;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    @TableLogic public int deleted;
}
