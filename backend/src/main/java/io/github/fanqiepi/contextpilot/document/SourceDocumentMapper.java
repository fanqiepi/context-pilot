package io.github.fanqiepi.contextpilot.document;

import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SourceDocumentMapper extends BaseMapper<SourceDocumentEntity> {

    @Update("""
            UPDATE source_document
            SET status = 'PROCESSING', error_summary = NULL,
                processing_attempts = processing_attempts + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{documentId} AND deleted = 0 AND status = 'PENDING'
            """)
    int claimForProcessing(@Param("documentId") UUID documentId);

    @Update("""
            UPDATE source_document
            SET status = 'SUCCEEDED', error_summary = NULL,
                embedding_profile_id = #{profile.id},
                embedding_provider = #{profile.provider},
                embedding_model = #{profile.model},
                embedding_dimensions = #{profile.dimensions},
                embedding_profile_version = #{profile.version},
                indexed_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{documentId} AND deleted = 0 AND status = 'PROCESSING'
            """)
    int markSucceeded(
            @Param("documentId") UUID documentId,
            @Param("profile") EmbeddingIndexProfile profile);

    @Update("""
            UPDATE source_document
            SET status = 'FAILED', error_summary = #{errorSummary},
                embedding_profile_id = NULL, embedding_provider = NULL,
                embedding_model = NULL, embedding_dimensions = NULL,
                embedding_profile_version = NULL, indexed_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{documentId} AND deleted = 0 AND status = 'PROCESSING'
            """)
    int markFailed(
            @Param("documentId") UUID documentId,
            @Param("errorSummary") String errorSummary);

    @Update("""
            UPDATE source_document
            SET status = 'FAILED', error_summary = #{errorSummary},
                embedding_profile_id = NULL, embedding_provider = NULL,
                embedding_model = NULL, embedding_dimensions = NULL,
                embedding_profile_version = NULL, indexed_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{documentId} AND deleted = 0 AND status = 'PENDING'
            """)
    int markSubmissionFailed(
            @Param("documentId") UUID documentId,
            @Param("errorSummary") String errorSummary);

    @Update("""
            UPDATE source_document
            SET status = 'PENDING', error_summary = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{documentId} AND deleted = 0 AND status = 'FAILED'
            """)
    int prepareRetry(@Param("documentId") UUID documentId);

    @Update("""
            UPDATE source_document
            SET status = 'PENDING', error_summary = NULL, processing_attempts = 0,
                embedding_profile_id = NULL, embedding_provider = NULL,
                embedding_model = NULL, embedding_dimensions = NULL,
                embedding_profile_version = NULL, indexed_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{documentId} AND deleted = 0 AND status = 'SUCCEEDED'
              AND embedding_profile_id IS DISTINCT FROM #{currentProfileId}
            """)
    int prepareReindex(
            @Param("documentId") UUID documentId,
            @Param("currentProfileId") String currentProfileId);

    @Update("""
            UPDATE source_document
            SET status = 'DELETING', updated_at = CURRENT_TIMESTAMP
            WHERE id = #{documentId} AND deleted = 0
            """)
    int markDeleting(@Param("documentId") UUID documentId);
}
