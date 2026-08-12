package io.github.fanqiepi.contextpilot.feedback;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AnswerFeedbackMapper extends BaseMapper<AnswerFeedbackEntity> {

    @Insert("""
            INSERT INTO answer_feedback (id, message_id, created_at, updated_at, deleted)
            VALUES (#{id}, #{messageId}, #{createdAt}, #{updatedAt}, 0)
            ON CONFLICT (message_id) DO UPDATE
            SET deleted = 0,
                created_at = CASE
                    WHEN answer_feedback.deleted = 1 THEN EXCLUDED.created_at
                    ELSE answer_feedback.created_at
                END,
                updated_at = CASE
                    WHEN answer_feedback.deleted = 1 THEN EXCLUDED.updated_at
                    ELSE answer_feedback.updated_at
                END
            """)
    int markHelpful(AnswerFeedbackEntity entity);
}
