package io.github.fanqiepi.contextpilot.knowledgebase;

import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseEntity> {

    @Select("SELECT COUNT(*) FROM source_document WHERE knowledge_base_id = #{knowledgeBaseId} AND deleted = 0")
    long countActiveDocuments(@Param("knowledgeBaseId") UUID knowledgeBaseId);
}
