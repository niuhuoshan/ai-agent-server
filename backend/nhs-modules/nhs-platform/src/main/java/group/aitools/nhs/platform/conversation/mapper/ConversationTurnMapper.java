package group.aitools.nhs.platform.conversation.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.domain.AgentConversationAttachment;
import group.aitools.nhs.platform.conversation.domain.AgentConversationTurn;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取OwnedActive会话。
 *
 * 定义会话会话回合相关的数据访问契约。
 * Persistence boundary for owner-scoped human conversation turns and attachments. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface ConversationTurnMapper {

    @Select("""
        SELECT id, user_id, project_id, task_id, agent_id, agent_version_id, branch_id,
               parent_conversation_id, fork_message_id, context_cutoff_sequence, principal_type,
               title, visibility, status, session_key, last_message_at, summary,
               create_by, create_time, update_by, update_time, del_flag
        FROM agent_conversation
        WHERE id = #{conversationId} AND user_id = #{userId}
          AND principal_type = 'human' AND status = 'active' AND del_flag = '0'
        """)
    AgentConversation selectOwnedActiveConversation(
        @Param("conversationId") Long conversationId,
        @Param("userId") Long userId
    );

    /**
     * 处理lockOwnedActive会话并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, user_id, project_id, task_id, agent_id, agent_version_id, branch_id,
               parent_conversation_id, fork_message_id, context_cutoff_sequence, principal_type,
               title, visibility, status, session_key, last_message_at, summary,
               create_by, create_time, update_by, update_time, del_flag
        FROM agent_conversation
        WHERE id = #{conversationId} AND user_id = #{userId}
          AND principal_type = 'human' AND status = 'active' AND del_flag = '0'
        FOR UPDATE
        """)
    AgentConversation lockOwnedActiveConversation(
        @Param("conversationId") Long conversationId,
        @Param("userId") Long userId
    );

    /**
     * 获取会话回合ByKey。
     *
     * @param conversationId 资源标识
     * @param idempotencyHash {@code idempotencyHash}参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, conversation_id, user_id, idempotency_hash, request_hash, trace_id,
               agent_id, agent_version_id, status, runtime_snapshot_json::text AS runtime_snapshot_json,
               error_summary, response_draft, stop_requested_at, started_at, finished_at
        FROM agent_conversation_turn
        WHERE conversation_id = #{conversationId} AND idempotency_hash = #{idempotencyHash}
        """)
    AgentConversationTurn selectTurnByKey(
        @Param("conversationId") Long conversationId,
        @Param("idempotencyHash") String idempotencyHash
    );

    /**
     * 获取Owned会话回合。
     *
     * @param conversationId 资源标识
     * @param turnId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, conversation_id, user_id, idempotency_hash, request_hash, trace_id,
               agent_id, agent_version_id, status, runtime_snapshot_json::text AS runtime_snapshot_json,
               error_summary, response_draft, stop_requested_at, started_at, finished_at
        FROM agent_conversation_turn
        WHERE id = #{turnId} AND conversation_id = #{conversationId} AND user_id = #{userId}
        """)
    AgentConversationTurn selectOwnedTurn(
        @Param("conversationId") Long conversationId,
        @Param("turnId") Long turnId,
        @Param("userId") Long userId
    );

    /**
     * 获取Owned会话回合By链路追踪。
     *
     * @param traceId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT t.id, t.conversation_id, t.user_id, t.idempotency_hash, t.request_hash,
               t.trace_id, t.agent_id, t.agent_version_id, t.status,
               t.runtime_snapshot_json::text AS runtime_snapshot_json,
               t.error_summary, t.response_draft, t.stop_requested_at, t.started_at, t.finished_at
        FROM agent_conversation_turn t
        INNER JOIN agent_conversation c ON c.id = t.conversation_id
        WHERE t.trace_id = #{traceId} AND t.user_id = #{userId}
          AND c.user_id = #{userId} AND c.principal_type = 'human' AND c.del_flag = '0'
        """)
    AgentConversationTurn selectOwnedTurnByTrace(
        @Param("traceId") String traceId,
        @Param("userId") Long userId
    );

    /**
     * 获取链路追踪Messages。
     *
     * @param conversationId 资源标识
     * @param traceId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, conversation_id, seq_no AS sequence_no, trace_id, role, content,
               agent_id, agent_version_id, model_id, status,
               prompt_tokens, completion_tokens, total_tokens, created_at
        FROM agent_conversation_message
        WHERE conversation_id = #{conversationId} AND trace_id = #{traceId}
        ORDER BY seq_no, id
        """)
    List<ConversationMessageRow> selectTraceMessages(
        @Param("conversationId") Long conversationId,
        @Param("traceId") String traceId
    );

    /**
     * 获取用户消息By链路追踪。
     *
     * @param conversationId 资源标识
     * @param traceId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, conversation_id, seq_no AS sequence_no, trace_id, role, content,
               agent_id, agent_version_id, model_id, status,
               prompt_tokens, completion_tokens, total_tokens, created_at
        FROM agent_conversation_message
        WHERE conversation_id = #{conversationId} AND trace_id = #{traceId} AND role = 'user'
        ORDER BY seq_no, id
        LIMIT 1
        """)
    ConversationMessageRow selectUserMessageByTrace(
        @Param("conversationId") Long conversationId,
        @Param("traceId") String traceId
    );

    /**
     * 处理lock会话回合并返回对应结果。
     *
     * @param turnId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, conversation_id, user_id, idempotency_hash, request_hash, trace_id,
               agent_id, agent_version_id, status, runtime_snapshot_json::text AS runtime_snapshot_json,
               error_summary, response_draft, stop_requested_at, started_at, finished_at
        FROM agent_conversation_turn
        WHERE id = #{turnId}
        FOR UPDATE
        """)
    AgentConversationTurn lockTurn(@Param("turnId") Long turnId);

    /**
     * 获取Active会话回合。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, conversation_id, user_id, idempotency_hash, request_hash, trace_id,
               agent_id, agent_version_id, status, runtime_snapshot_json::text AS runtime_snapshot_json,
               error_summary, response_draft, stop_requested_at, started_at, finished_at
        FROM agent_conversation_turn
        WHERE conversation_id = #{conversationId}
          AND status IN ('running', 'stopping', 'waiting_confirmation', 'waiting_user_question')
        ORDER BY started_at DESC, id DESC
        LIMIT 1
        """)
    AgentConversationTurn selectActiveTurn(@Param("conversationId") Long conversationId);

    /**
     * 创建并保存会话回合。
     *
     * @param turn 会话回合参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_conversation_turn (
            id, conversation_id, user_id, idempotency_hash, request_hash, trace_id,
            agent_id, agent_version_id, status, runtime_snapshot_json, started_at
        ) VALUES (
            #{id}, #{conversationId}, #{userId}, #{idempotencyHash}, #{requestHash}, #{traceId},
            #{agentId}, #{agentVersionId}, #{status}, CAST(#{runtimeSnapshotJson} AS jsonb), #{startedAt}
        ) ON CONFLICT DO NOTHING
        """)
    int insertTurn(AgentConversationTurn turn);

    /**
     * 获取{@code RecentMessages}。
     *
     * @param conversationId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, conversation_id, seq_no AS sequence_no, trace_id, role, content,
               agent_id, agent_version_id, model_id, status,
               prompt_tokens, completion_tokens, total_tokens, created_at
        FROM agent_conversation_message
        WHERE conversation_id = #{conversationId}
        ORDER BY seq_no DESC, id DESC
        LIMIT #{limit}
        """)
    List<ConversationMessageRow> selectRecentMessages(
        @Param("conversationId") Long conversationId,
        @Param("limit") int limit
    );

    /**
 * 获取上下文Messages。
 *
     * Returns the context visible to a new turn. The current branch is always
     * included; each ancestor is bounded by the cutoff stored on its direct
     * child. Cutoff sequence numbers belong to the parent conversation and
     * must never truncate messages created later on the child branch.
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
        SELECT recent.id, recent.conversation_id, recent.sequence_no, recent.trace_id,
               recent.role, recent.content, recent.agent_id, recent.agent_version_id,
               recent.model_id, recent.status, recent.prompt_tokens,
               recent.completion_tokens, recent.total_tokens, recent.created_at
        FROM (
            SELECT m.id, m.conversation_id, m.seq_no AS sequence_no, m.trace_id,
                   m.role, m.content, m.agent_id, m.agent_version_id, m.model_id,
                   m.status, m.prompt_tokens, m.completion_tokens, m.total_tokens,
                   m.created_at
            FROM lineage l
            JOIN agent_conversation_message m ON m.conversation_id = l.id
            WHERE l.depth = 0
               OR (l.depth > 0 AND m.seq_no <= (
                    SELECT COALESCE(child.context_cutoff_sequence, 2147483647)
                    FROM lineage child WHERE child.depth = l.depth - 1
                  ))
            ORDER BY m.created_at DESC, m.id DESC
            LIMIT #{limit}
        ) recent
        ORDER BY recent.created_at, recent.id
        """)
    List<ConversationMessageRow> selectContextMessages(
        @Param("conversationId") Long conversationId,
        @Param("limit") int limit
    );

    /**
     * 处理next消息Sequence并返回对应结果。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @Select("SELECT COALESCE(max(seq_no), 0) + 1 FROM agent_conversation_message WHERE conversation_id = #{conversationId}")
    int nextMessageSequence(@Param("conversationId") Long conversationId);

    /**
     * 创建并保存消息。
     *
     * @param id 资源标识
     * @param conversationId 资源标识
     * @param sequenceNo 起始位置或序号
     * @param traceId 资源标识
     * @param role 角色参数
     * @param content 待处理内容
     * @param contentJson 待处理内容
     * @param agentId 资源标识
     * @param agentVersionId 资源标识
     * @param status 目标状态
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_conversation_message (
            id, conversation_id, seq_no, trace_id, role, content, content_json,
            agent_id, agent_version_id, status, created_at
        ) VALUES (
            #{id}, #{conversationId}, #{sequenceNo}, #{traceId}, #{role}, #{content},
            CAST(#{contentJson} AS jsonb), #{agentId}, #{agentVersionId}, #{status}, #{now}
        )
        """)
    int insertMessage(
        @Param("id") Long id,
        @Param("conversationId") Long conversationId,
        @Param("sequenceNo") int sequenceNo,
        @Param("traceId") String traceId,
        @Param("role") String role,
        @Param("content") String content,
        @Param("contentJson") String contentJson,
        @Param("agentId") Long agentId,
        @Param("agentVersionId") Long agentVersionId,
        @Param("status") String status,
        @Param("now") LocalDateTime now
    );

    /**
     * 将输入数据转换为uch会话。
     *
     * @param conversationId 资源标识
     * @param userId 资源标识
     * @param agentId 资源标识
     * @param agentVersionId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_conversation
        SET agent_id = #{agentId}, agent_version_id = #{agentVersionId},
            last_message_at = #{now}, update_by = #{userId}, update_time = #{now}
        WHERE id = #{conversationId} AND user_id = #{userId}
          AND principal_type = 'human' AND status = 'active' AND del_flag = '0'
        """)
    int touchConversation(
        @Param("conversationId") Long conversationId,
        @Param("userId") Long userId,
        @Param("agentId") Long agentId,
        @Param("agentVersionId") Long agentVersionId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code requestStop}并返回对应结果。
     *
     * @param turnId 资源标识
     * @param userId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_conversation_turn
        SET status = 'stopping', stop_requested_at = #{now}
        WHERE id = #{turnId} AND user_id = #{userId}
          AND status IN ('running', 'waiting_confirmation', 'waiting_user_question')
        """)
    int requestStop(
        @Param("turnId") Long turnId,
        @Param("userId") Long userId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code markWaitingConfirmation}并返回对应结果。
     *
     * @param turnId 资源标识
     * @param responseDraft {@code responseDraft}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_conversation_turn
        SET status = 'waiting_confirmation', error_summary = NULL, response_draft = #{responseDraft}
        WHERE id = #{turnId} AND status = 'running'
        """)
    int markWaitingConfirmation(
        @Param("turnId") Long turnId,
        @Param("responseDraft") String responseDraft
    );

    /**
     * 处理markWaiting用户追问并返回对应结果。
     *
     * @param turnId 资源标识
     * @param responseDraft {@code responseDraft}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_conversation_turn
        SET status = 'waiting_user_question', error_summary = NULL, response_draft = #{responseDraft}
        WHERE id = #{turnId} AND status = 'running'
        """)
    int markWaitingUserQuestion(
        @Param("turnId") Long turnId,
        @Param("responseDraft") String responseDraft
    );

    /**
     * 更新运行时快照。
     *
     * @param turnId 资源标识
     * @param runtimeSnapshotJson 运行时快照Json参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_conversation_turn
        SET runtime_snapshot_json = CAST(#{runtimeSnapshotJson} AS jsonb)
        WHERE id = #{turnId} AND status = 'running'
        """)
    int updateRuntimeSnapshot(
        @Param("turnId") Long turnId,
        @Param("runtimeSnapshotJson") String runtimeSnapshotJson
    );

    /**
     * 处理{@code claimConfirmationResume}并返回对应结果。
     *
     * @param turnId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_conversation_turn
        SET status = 'running', error_summary = NULL, finished_at = NULL
        WHERE id = #{turnId} AND user_id = #{userId} AND status = 'waiting_confirmation'
        """)
    int claimConfirmationResume(
        @Param("turnId") Long turnId,
        @Param("userId") Long userId
    );

    /**
     * 处理claim用户追问Resume并返回对应结果。
     *
     * @param turnId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_conversation_turn
        SET status = 'running', error_summary = NULL, finished_at = NULL, stop_requested_at = NULL
        WHERE id = #{turnId} AND user_id = #{userId} AND status = 'waiting_user_question'
        """)
    int claimUserQuestionResume(
        @Param("turnId") Long turnId,
        @Param("userId") Long userId
    );

    /**
 * 处理{@code stopRequested}并返回对应结果。
 *
     * Reads the durable stop fact without loading the runtime snapshot.  A
     * worker on another JVM uses this value to interrupt its local runtime.
     */
    @Select("""
        SELECT status = 'stopping' OR stop_requested_at IS NOT NULL
        FROM agent_conversation_turn
        WHERE id = #{turnId}
        """)
    Boolean stopRequested(@Param("turnId") Long turnId);

    /**
     * 处理finish会话回合并返回对应结果。
     *
     * @param turnId 资源标识
     * @param status 目标状态
     * @param errorSummary {@code errorSummary}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_conversation_turn
        SET status = #{status}, error_summary = #{errorSummary}, response_draft = NULL, finished_at = #{now}
        WHERE id = #{turnId} AND status IN ('running', 'stopping')
        """)
    int finishTurn(
        @Param("turnId") Long turnId,
        @Param("status") String status,
        @Param("errorSummary") String errorSummary,
        @Param("now") LocalDateTime now
    );

    /**
     * 更新会话Summary。
     *
     * @param conversationId 资源标识
     * @param userId 资源标识
     * @param summary {@code summary}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_conversation
        SET summary = #{summary}, last_message_at = #{now}, update_time = #{now}
        WHERE id = #{conversationId} AND user_id = #{userId}
          AND principal_type = 'human' AND del_flag = '0'
        """)
    int updateConversationSummary(
        @Param("conversationId") Long conversationId,
        @Param("userId") Long userId,
        @Param("summary") String summary,
        @Param("now") LocalDateTime now
    );

    /**
     * 创建并保存附件。
     *
     * @param attachment 附件参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_conversation_attachment (
            id, conversation_id, user_id, original_name, storage_type, storage_ref,
            mime_type, size_bytes, sha256, status, created_at
        ) VALUES (
            #{id}, #{conversationId}, #{userId}, #{originalName}, #{storageType}, #{storageRef},
            #{mimeType}, #{sizeBytes}, #{sha256}, #{status}, #{createdAt}
        )
        """)
    int insertAttachment(AgentConversationAttachment attachment);

    /**
     * 获取Owned附件。
     *
     * @param conversationId 资源标识
     * @param attachmentId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, conversation_id, user_id, turn_id, original_name, storage_type,
               storage_ref, mime_type, size_bytes, sha256, status, created_at
        FROM agent_conversation_attachment
        WHERE id = #{attachmentId} AND conversation_id = #{conversationId}
          AND user_id = #{userId} AND status <> 'deleted'
        """)
    AgentConversationAttachment selectOwnedAttachment(
        @Param("conversationId") Long conversationId,
        @Param("attachmentId") Long attachmentId,
        @Param("userId") Long userId
    );

    /**
     * 获取{@code OwnedAttachments}。
     *
     * @param conversationId 资源标识
     * @param userId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, conversation_id, user_id, turn_id, original_name, storage_type,
               storage_ref, mime_type, size_bytes, sha256, status, created_at
        FROM agent_conversation_attachment
        WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND status <> 'deleted'
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentConversationAttachment> selectOwnedAttachments(
        @Param("conversationId") Long conversationId,
        @Param("userId") Long userId,
        @Param("limit") int limit
    );

    /**
     * 处理bind附件并返回对应结果。
     *
     * @param attachmentId 资源标识
     * @param conversationId 资源标识
     * @param userId 资源标识
     * @param turnId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_conversation_attachment
        SET turn_id = #{turnId}, status = 'bound'
        WHERE id = #{attachmentId} AND conversation_id = #{conversationId}
          AND user_id = #{userId} AND turn_id IS NULL AND status = 'ready'
        """)
    int bindAttachment(
        @Param("attachmentId") Long attachmentId,
        @Param("conversationId") Long conversationId,
        @Param("userId") Long userId,
        @Param("turnId") Long turnId
    );
}
