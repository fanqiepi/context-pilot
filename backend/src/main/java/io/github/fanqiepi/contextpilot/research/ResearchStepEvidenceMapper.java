package io.github.fanqiepi.contextpilot.research;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ResearchStepEvidenceMapper {
    @Insert("""
            INSERT INTO research_step_evidence (
                id, step_id, evidence_id, rank_index, score, created_at, updated_at, deleted
            ) VALUES (
                #{id}, #{stepId}, #{evidenceId}, #{rankIndex}, #{score},
                #{createdAt}, #{updatedAt}, #{deleted}
            )
            ON CONFLICT DO NOTHING
            """)
    int insert(ResearchStepEvidenceEntity entity);
}
