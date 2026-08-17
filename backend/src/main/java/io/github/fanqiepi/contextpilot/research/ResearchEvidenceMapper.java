package io.github.fanqiepi.contextpilot.research;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ResearchEvidenceMapper {
    @Insert("""
            INSERT INTO research_evidence (
                id, run_id, document_id, vector_id, original_filename, chunk_index,
                page_number, embedding_profile_id, score, excerpt,
                created_at, updated_at, deleted
            ) VALUES (
                #{id}, #{runId}, #{documentId}, #{vectorId}, #{originalFilename}, #{chunkIndex},
                #{pageNumber}, #{embeddingProfileId}, #{score}, #{excerpt},
                #{createdAt}, #{updatedAt}, #{deleted}
            )
            """)
    int insert(ResearchEvidenceEntity entity);

    @Select("""
            SELECT id, run_id, document_id, vector_id, original_filename, chunk_index,
                   page_number, embedding_profile_id, score, excerpt,
                   created_at, updated_at, deleted
            FROM research_evidence WHERE run_id = #{runId} AND deleted = 0 ORDER BY created_at, id
            """)
    List<ResearchEvidenceEntity> selectByRunId(UUID runId);
}
