package group.aitools.nhs.platform.conversation.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.conversation.domain.AgentChatFeedback;
import group.aitools.nhs.platform.conversation.domain.AgentChatResourceScope;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取消息。
 *
 * 定义会话Governance相关的数据访问契约。
 * Owner-scoped persistence for feedback and conversation resource controls. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface ConversationGovernanceMapper {

    @Select("""
        SELECT id, conversation_id, seq_no AS sequence_no, trace_id, role, content,
               agent_id, agent_version_id, model_id, status,
               prompt_tokens, completion_tokens, total_tokens, created_at
        FROM agent_conversation_message
        WHERE id = #{messageId} AND conversation_id = #{conversationId}
        """)
    ConversationMessageRow selectMessage(
        @Param("conversationId") Long conversationId,
        @Param("messageId") Long messageId
    );

    /**
     * 获取Previous用户消息。
     *
     * @param conversationId 资源标识
     * @param sequenceNo 起始位置或序号
     * @return 处理结果
     */
    @Select("""
        SELECT id, conversation_id, seq_no AS sequence_no, trace_id, role, content,
               agent_id, agent_version_id, model_id, status,
               prompt_tokens, completion_tokens, total_tokens, created_at
        FROM agent_conversation_message
        WHERE conversation_id = #{conversationId} AND seq_no < #{sequenceNo} AND role = 'user'
        ORDER BY seq_no DESC, id DESC
        LIMIT 1
        """)
    ConversationMessageRow selectPreviousUserMessage(
        @Param("conversationId") Long conversationId,
        @Param("sequenceNo") Integer sequenceNo
    );

    /**
     * 获取反馈。
     *
     * @param conversationId 资源标识
     * @param messageId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, conversation_id, message_id, turn_id, user_id, rating, reason,
               comment, trace_id, created_at, updated_at
        FROM agent_chat_feedback
        WHERE conversation_id = #{conversationId} AND message_id = #{messageId}
          AND user_id = #{userId}
        """)
    AgentChatFeedback selectFeedback(
        @Param("conversationId") Long conversationId,
        @Param("messageId") Long messageId,
        @Param("userId") Long userId
    );

    /**
     * 创建并保存反馈。
     *
     * @param feedback 反馈参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_chat_feedback (
            id, conversation_id, message_id, turn_id, user_id, rating, reason,
            comment, trace_id, created_at, updated_at
        ) VALUES (
            #{id}, #{conversationId}, #{messageId}, #{turnId}, #{userId}, #{rating},
            #{reason}, #{comment}, #{traceId}, #{createdAt}, #{updatedAt}
        ) ON CONFLICT (conversation_id, message_id, user_id) DO NOTHING
        """)
    int insertFeedback(AgentChatFeedback feedback);

    /**
     * 更新反馈。
     *
     * @param feedback 反馈参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chat_feedback
        SET turn_id = #{turnId}, rating = #{rating}, reason = #{reason}, comment = #{comment},
            trace_id = #{traceId}, updated_at = #{updatedAt}
        WHERE conversation_id = #{conversationId} AND message_id = #{messageId}
          AND user_id = #{userId}
        """)
    int updateFeedback(AgentChatFeedback feedback);

    /**
     * 获取资源范围。
     *
     * @param conversationId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT conversation_id, user_id, scope_json::text AS scope_json, revision,
               created_at, updated_at
        FROM agent_chat_resource_scope
        WHERE conversation_id = #{conversationId} AND user_id = #{userId}
        """)
    AgentChatResourceScope selectResourceScope(
        @Param("conversationId") Long conversationId,
        @Param("userId") Long userId
    );

    /**
     * 创建并保存资源范围。
     *
     * @param conversationId 资源标识
     * @param userId 资源标识
     * @param scopeJson 范围Json参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_chat_resource_scope (
            conversation_id, user_id, scope_json, revision, created_at, updated_at
        ) VALUES (
            #{conversationId}, #{userId}, CAST(#{scopeJson} AS jsonb), 1, #{now}, #{now}
        ) ON CONFLICT (conversation_id) DO NOTHING
        """)
    int insertResourceScope(
        @Param("conversationId") Long conversationId,
        @Param("userId") Long userId,
        @Param("scopeJson") String scopeJson,
        @Param("now") LocalDateTime now
    );

    /**
     * 更新资源范围。
     *
     * @param conversationId 资源标识
     * @param userId 资源标识
     * @param scopeJson 范围Json参数
     * @param expectedRevision {@code expectedRevision}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chat_resource_scope
        SET scope_json = CAST(#{scopeJson} AS jsonb), revision = revision + 1, updated_at = #{now}
        WHERE conversation_id = #{conversationId} AND user_id = #{userId}
          AND revision = #{expectedRevision}
        """)
    int updateResourceScope(
        @Param("conversationId") Long conversationId,
        @Param("userId") Long userId,
        @Param("scopeJson") String scopeJson,
        @Param("expectedRevision") int expectedRevision,
        @Param("now") LocalDateTime now
    );

    /**
     * 获取Active会话Id。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT state.active_conversation_id
        FROM agent_chat_user_state state
        JOIN agent_conversation conversation
          ON conversation.id = state.active_conversation_id
         AND conversation.user_id = state.user_id
         AND conversation.principal_type = 'human'
         AND conversation.status <> 'deleted'
         AND conversation.del_flag = '0'
        WHERE state.user_id = #{userId}
        """)
    Long selectActiveConversationId(@Param("userId") Long userId);

    /**
     * 处理upsertActive会话并返回对应结果。
     *
     * @param userId 资源标识
     * @param conversationId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_chat_user_state (
            user_id, active_conversation_id, created_at, updated_at
        ) VALUES (
            #{userId}, #{conversationId}, #{now}, #{now}
        )
        ON CONFLICT (user_id) DO UPDATE
        SET active_conversation_id = EXCLUDED.active_conversation_id,
            updated_at = EXCLUDED.updated_at
        """)
    int upsertActiveConversation(
        @Param("userId") Long userId,
        @Param("conversationId") Long conversationId,
        @Param("now") LocalDateTime now
    );

    /**
     * 清理或重置Active会话。
     *
     * @param userId 资源标识
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @Delete("""
        DELETE FROM agent_chat_user_state
        WHERE user_id = #{userId} AND active_conversation_id = #{conversationId}
        """)
    int clearActiveConversation(
        @Param("userId") Long userId,
        @Param("conversationId") Long conversationId
    );

    /**
     * 删除会话。
     *
     * @param conversationId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_conversation
        SET status = 'deleted', del_flag = '1', update_by = #{userId}, update_time = CURRENT_TIMESTAMP
        WHERE id = #{conversationId} AND user_id = #{userId}
          AND principal_type = 'human' AND status <> 'deleted' AND del_flag = '0'
        """)
    int deleteConversation(
        @Param("conversationId") Long conversationId,
        @Param("userId") Long userId
    );
}
