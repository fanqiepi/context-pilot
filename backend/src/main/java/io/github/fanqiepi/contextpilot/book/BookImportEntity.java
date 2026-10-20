package io.github.fanqiepi.contextpilot.book;

import java.time.OffsetDateTime;
import java.util.UUID;
import com.baomidou.mybatisplus.annotation.*;

@TableName("book_import")
public class BookImportEntity {
    @TableId(type=IdType.INPUT) public UUID id;
    public UUID ownerId;
    public UUID clientRequestId;
    public String requestHash;
    public String originalFilename;
    public String storageKey;
    public String fileHash;
    public long fileBytes;
    public boolean ownsFile;
    public String title;
    public String sourceDescription;
    public String parserId;
    public Integer headingLevel;
    public int parserVersion = 1;
    public String previewHash;
    public String previewJson;
    public String status;
    public OffsetDateTime expiresAt;
    public UUID targetBookId;
    public UUID resultVersionId;
    public String errorCode;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    @TableLogic public int deleted;
}
