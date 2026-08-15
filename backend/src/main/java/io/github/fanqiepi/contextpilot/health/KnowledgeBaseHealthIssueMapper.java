package io.github.fanqiepi.contextpilot.health;

import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeBaseHealthIssueMapper extends BaseMapper<KnowledgeBaseHealthIssueEntity> {

    @Select("""
            SELECT *
            FROM knowledge_base_health_issue
            WHERE id = #{issueId}
              AND report_id = #{reportId}
              AND deleted = 0
            FOR UPDATE
            """)
    KnowledgeBaseHealthIssueEntity selectForActionProposal(
            @Param("reportId") UUID reportId,
            @Param("issueId") UUID issueId);
}
