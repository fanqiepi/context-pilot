package io.github.fanqiepi.contextpilot.projectsource;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("project_source_preview")
public class ProjectPreviewEntity {
    @TableId(type = IdType.INPUT) public UUID id;
    public UUID ownerId;
    public UUID clientRequestId;
    public String requestHash;
    public String repository;
    public String requestedRef;
    public String pathsJson;
    public String resolvedCommit;
    public String status;
    public String errorCode;
    public String errorPath;
    public String traceId;
    public OffsetDateTime expiresAt;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    @TableLogic public int deleted;
}
