package io.github.fanqiepi.contextpilot.research;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ResearchDocumentMapper {

    @Select({
            "<script>",
            "SELECT sd.id, sd.knowledge_base_id, sd.original_filename, sd.status, sd.embedding_profile_id,",
            "EXISTS (SELECT 1 FROM vector_store vs",
            "WHERE vs.metadata ->> 'document_id' = sd.id::text",
            "AND vs.metadata ->> 'knowledge_base_id' = sd.knowledge_base_id::text",
            "AND vs.metadata ->> 'embedding_profile_id' = #{currentProfileId}) AS current_vector_present",
            "FROM source_document sd",
            "WHERE sd.deleted = 0 AND sd.id IN",
            "<foreach collection='documentIds' item='documentId' open='(' separator=',' close=')'>",
            "#{documentId}",
            "</foreach>",
            "ORDER BY sd.id",
            "</script>"
    })
    List<ResearchDocumentFact> selectFacts(
            @Param("documentIds") List<UUID> documentIds,
            @Param("currentProfileId") String currentProfileId);

    @Select({
            "<script>",
            "SELECT id, original_filename FROM source_document",
            "WHERE deleted = 0 AND id IN",
            "<foreach collection='documentIds' item='documentId' open='(' separator=',' close=')'>",
            "#{documentId}",
            "</foreach>",
            "ORDER BY id",
            "</script>"
    })
    List<ResearchDocumentFact> selectNames(@Param("documentIds") List<UUID> documentIds);
}
