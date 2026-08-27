package group.aitools.nhs.platform.conversation.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;

import java.util.List;

/**
 * 获取Owned会话。
 *
 * 定义智能体会话相关的数据访问契约。
 * Persistence operations that always retain private-conversation ownership boundaries. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AgentConversationMapper extends BaseMapper<AgentConversation> {

    @Select("""
        SELECT id, user_id, project_id, task_id, agent_id, agent_version_id, branch_id,
               parent_conversation_id, fork_message_id, context_cutoff_sequence, principal_type, title,
               visibility, status, session_key, last_message_at, summary,
               create_by, create_time, update_by, update_time, del_flag
        FROM agent_conversation
        WHERE id = #{conversationId}
          AND user_id = #{userId}
          AND principal_type = 'human'
          AND del_flag = '0'
          AND status <> 'deleted'
          AND NOT EXISTS (
              SELECT 1 FROM agent_conversation_history_tombstone h
              WHERE h.user_id = #{userId} AND h.conversation_id = agent_conversation.id
                AND h.trace_id IS NULL
          )
        """)
    AgentConversation selectOwnedConversation(
        @Param("conversationId") Long conversationId,
        @Param("userId") Long userId
    );

    /**
 * 处理lockOwned会话并返回对应结果。
 *
     * Locks one owner-bound conversation while a lifecycle operation (for
     * example finalize) reads and updates its durable summary.  The lock is
     * deliberately scoped to the human owner and excludes soft-deleted rows.
     */
    @Select("""
        SELECT id, user_id, project_id, task_id, agent_id, agent_version_id, branch_id,
               parent_conversation_id, fork_message_id, context_cutoff_sequence, principal_type, title,
               visibility, status, session_key, last_message_at, summary,
               create_by, create_time, update_by, update_time, del_flag
        FROM agent_conversation
        WHERE id = #{conversationId}
          AND user_id = #{userId}
          AND principal_type = 'human'
          AND del_flag = '0'
          AND status <> 'deleted'
        FOR UPDATE
        """)
    AgentConversation lockOwnedConversation(
        @Param("conversationId") Long conversationId,
        @Param("userId") Long userId
    );

    /**
     * 获取{@code RecentOwnedConversations}。
     *
     * @param userId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, user_id, project_id, task_id, agent_id, agent_version_id, branch_id,
               parent_conversation_id, fork_message_id, context_cutoff_sequence, principal_type, title,
               visibility, status, session_key, last_message_at, summary,
               create_by, create_time, update_by, update_time, del_flag
        FROM agent_conversation
        WHERE user_id = #{userId}
          AND principal_type = 'human'
          AND del_flag = '0'
          AND status <> 'deleted'
        ORDER BY COALESCE(last_message_at, create_time) DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentConversation> selectRecentOwnedConversations(
        @Param("userId") Long userId,
        @Param("limit") int limit
    );

    /**
     * 查询{@code OwnedConversations}列表。
     *
     * @param userId 资源标识
     * @param query 查询参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, user_id, project_id, task_id, agent_id, agent_version_id, branch_id,
               parent_conversation_id, fork_message_id, context_cutoff_sequence, principal_type, title,
               visibility, status, session_key, last_message_at, summary,
               create_by, create_time, update_by, update_time, del_flag
        FROM agent_conversation
        WHERE user_id = #{userId}
          AND principal_type = 'human'
          AND del_flag = '0'
          AND status <> 'deleted'
          AND (title ILIKE CONCAT('%', #{query}, '%') OR summary ILIKE CONCAT('%', #{query}, '%'))
          AND NOT EXISTS (
              SELECT 1 FROM agent_conversation_history_tombstone h
              WHERE h.user_id = #{userId} AND h.conversation_id = agent_conversation.id
                AND h.trace_id IS NULL
          )
        ORDER BY COALESCE(last_message_at, create_time) DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentConversation> searchOwnedConversations(
        @Param("userId") Long userId,
        @Param("query") String query,
        @Param("limit") int limit
    );

    /**
     * 获取{@code Messages}。
     *
     * @param conversationId 资源标识
     * @param afterSequence 起始位置或序号
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, conversation_id, seq_no AS sequence_no, trace_id, role, content,
               agent_id, agent_version_id, model_id, status,
               prompt_tokens, completion_tokens, total_tokens, created_at
        FROM agent_conversation_message
        WHERE conversation_id = #{conversationId}
          AND seq_no > #{afterSequence}
        ORDER BY seq_no, id
        LIMIT #{limit}
        """)
    List<ConversationMessageRow> selectMessages(
        @Param("conversationId") Long conversationId,
        @Param("afterSequence") int afterSequence,
        @Param("limit") int limit
    );

    /**
     * 获取{@code LineageMessages}。
     *
     * @param conversationId 资源标识
     * @param afterSequence 起始位置或序号
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        WITH RECURSIVE lineage AS (
            SELECT id, parent_conversation_id, context_cutoff_sequence, 0 AS depth,
                   CAST(id AS TEXT) AS path
            FROM agent_conversation
            WHERE id = #{conversationId}
            UNION ALL
            SELECT parent.id, parent.parent_conversation_id, parent.context_cutoff_sequence,
                   lineage.depth + 1, lineage.path || '/' || parent.id
            FROM agent_conversation parent
            JOIN lineage ON parent.id = lineage.parent_conversation_id
        ), visible AS (
            SELECT id, depth FROM lineage
        )
        SELECT m.id, m.conversation_id, m.seq_no AS sequence_no, m.trace_id, m.role, m.content,
               m.agent_id, m.agent_version_id, m.model_id, m.status,
               m.prompt_tokens, m.completion_tokens, m.total_tokens, m.created_at
        FROM visible v
        JOIN agent_conversation_message m ON m.conversation_id = v.id
        WHERE (v.depth = 0 AND m.seq_no > #{afterSequence})
           OR (v.depth > 0 AND m.seq_no <= (
                SELECT COALESCE(parent.context_cutoff_sequence, 2147483647)
                FROM lineage parent
                WHERE parent.depth = v.depth - 1
              ))
        ORDER BY v.depth DESC, m.seq_no, m.id
        LIMIT #{limit}
        """)
    List<ConversationMessageRow> selectLineageMessages(
        @Param("conversationId") Long conversationId,
        @Param("afterSequence") int afterSequence,
        @Param("limit") int limit
    );

    /**
     * 获取上下文Messages。
     *
     * @param conversationId 资源标识
     * @param contextCutoffSequence 起始位置或序号
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        WITH RECURSIVE lineage AS (
            SELECT id, parent_conversation_id, context_cutoff_sequence, 0 AS depth
            FROM agent_conversation WHERE id = #{conversationId}
            UNION ALL
            SELECT parent.id, parent.parent_conversation_id, parent.context_cutoff_sequence,
                   lineage.depth + 1
            FROM agent_conversation parent JOIN lineage
              ON parent.id = lineage.parent_conversation_id
        )
        SELECT m.id, m.conversation_id, m.seq_no AS sequence_no, m.trace_id, m.role, m.content,
               m.agent_id, m.agent_version_id, m.model_id, m.status,
               m.prompt_tokens, m.completion_tokens, m.total_tokens, m.created_at
        FROM lineage l
        JOIN agent_conversation_message m ON m.conversation_id = l.id
        WHERE (l.depth = 0 AND m.seq_no <= #{contextCutoffSequence})
           OR (l.depth > 0 AND m.seq_no <= (
                SELECT COALESCE(MIN(x.context_cutoff_sequence), 2147483647)
                FROM lineage x WHERE x.depth < l.depth
              ))
        ORDER BY l.depth DESC, m.seq_no, m.id
        LIMIT #{limit}
        """)
    List<ConversationMessageRow> selectContextMessages(
        @Param("conversationId") Long conversationId,
        @Param("contextCutoffSequence") int contextCutoffSequence,
        @Param("limit") int limit
    );

    /**
     * 获取OwnedFork消息。
     *
     * @param conversationId 资源标识
     * @param messageId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT m.id, m.conversation_id, m.seq_no AS sequence_no, m.trace_id, m.role, m.content,
               m.agent_id, m.agent_version_id, m.model_id, m.status,
               m.prompt_tokens, m.completion_tokens, m.total_tokens, m.created_at
        FROM agent_conversation_message m
        INNER JOIN agent_conversation c ON c.id = m.conversation_id
        WHERE m.id = #{messageId} AND c.id = #{conversationId}
          AND c.user_id = #{userId} AND c.principal_type = 'human'
          AND c.del_flag = '0' AND c.status = 'active'
        """)
    ConversationMessageRow selectOwnedForkMessage(
        @Param("conversationId") Long conversationId,
        @Param("messageId") Long messageId,
        @Param("userId") Long userId
    );

    /**
     * 获取{@code OwnedBranch}。
     *
     * @param userId 资源标识
     * @param branchId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, user_id, project_id, task_id, agent_id, agent_version_id, branch_id,
               parent_conversation_id, fork_message_id, context_cutoff_sequence, principal_type, title,
               visibility, status, session_key, last_message_at, summary,
               create_by, create_time, update_by, update_time, del_flag
        FROM agent_conversation
        WHERE user_id = #{userId} AND branch_id = #{branchId}
          AND principal_type = 'human' AND del_flag = '0' AND status <> 'deleted'
        """)
    AgentConversation selectOwnedBranch(
        @Param("userId") Long userId,
        @Param("branchId") String branchId
    );

    /**
     * 处理copy资源范围并返回对应结果。
     *
     * @param parentConversationId 资源标识
     * @param childConversationId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_chat_resource_scope (
            conversation_id, user_id, scope_json, revision, created_at, updated_at
        )
        SELECT #{childConversationId}, user_id, scope_json, revision, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        FROM agent_chat_resource_scope
        WHERE conversation_id = #{parentConversationId} AND user_id = #{userId}
        ON CONFLICT (conversation_id) DO NOTHING
        """)
    int copyResourceScope(
        @Param("parentConversationId") Long parentConversationId,
        @Param("childConversationId") Long childConversationId,
        @Param("userId") Long userId
    );

    /**
     * 获取Active会话回合Id。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id FROM agent_conversation_turn
        WHERE conversation_id = #{conversationId}
          AND status IN ('running', 'stopping', 'waiting_confirmation', 'waiting_user_question')
        LIMIT 1
        """)
    Long selectActiveTurnId(@Param("conversationId") Long conversationId);

    /**
     * 处理link任务IfAbsent并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param userId 资源标识
     * @param taskId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_conversation
        SET task_id = #{taskId},
            update_by = #{userId},
            update_time = CURRENT_TIMESTAMP
        WHERE id = #{conversationId}
          AND user_id = #{userId}
          AND task_id IS NULL
          AND del_flag = '0'
        """)
    int linkTaskIfAbsent(
        @Param("conversationId") Long conversationId,
        @Param("userId") Long userId,
        @Param("taskId") Long taskId
    );
}
