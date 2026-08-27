package group.aitools.nhs.platform.embed.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.agent.persistence.row.AgentVersionBindingRow;
import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.domain.AgentConversationAttachment;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;
import group.aitools.nhs.platform.embed.domain.EmbedBrowserCredential;
import group.aitools.nhs.platform.embed.domain.EmbedSession;
import group.aitools.nhs.platform.embed.domain.EmbedTurn;
import group.aitools.nhs.platform.embed.persistence.row.EmbedAgentRuntimeRow;
import group.aitools.nhs.platform.execution.domain.AgentExecutionEvent;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定义嵌入式会话对话相关的数据访问契约。
 */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface EmbedChatMapper {

    /**
     * 获取智能体运行时。
     *
     * @param agentVersionId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT av.id AS agent_version_id, av.agent_id, d.agent_key,
               d.name AS agent_name, d.description AS agent_description, d.status AS agent_status,
               av.version_no, av.status AS version_status, av.published_at,
               av.system_prompt, av.model_id, av.synthesis_model_id,
               av.runtime_config_json::text AS runtime_config_json,
               av.welcome_config_json::text AS welcome_config_json,
               av.routing_tags_json::text AS routing_tags_json,
               av.content_hash
        FROM agent_definition_version av
        JOIN agent_definition d ON d.id = av.agent_id AND d.del_flag = '0'
        WHERE av.id = #{agentVersionId}
        """)
    EmbedAgentRuntimeRow selectAgentRuntime(@Param("agentVersionId") Long agentVersionId);

    /**
     * 获取Published智能体运行时。
     *
     * @param agentId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT av.id AS agent_version_id, av.agent_id, d.agent_key,
               d.name AS agent_name, d.description AS agent_description, d.status AS agent_status,
               av.version_no, av.status AS version_status, av.published_at,
               av.system_prompt, av.model_id, av.synthesis_model_id,
               av.runtime_config_json::text AS runtime_config_json,
               av.welcome_config_json::text AS welcome_config_json,
               av.routing_tags_json::text AS routing_tags_json,
               av.content_hash
        FROM agent_definition d
        JOIN agent_definition_version av ON av.agent_id = d.id AND av.status = 'published'
        WHERE d.id = #{agentId} AND d.status = 'active' AND d.del_flag = '0'
        ORDER BY av.version_no DESC, av.id DESC
        LIMIT 1
        """)
    EmbedAgentRuntimeRow selectPublishedAgentRuntime(@Param("agentId") Long agentId);

    /**
     * 获取Default智能体运行时。
     *
     * @return 处理结果
     */
    @Select("""
        SELECT av.id AS agent_version_id, av.agent_id, d.agent_key,
               d.name AS agent_name, d.description AS agent_description, d.status AS agent_status,
               av.version_no, av.status AS version_status, av.published_at,
               av.system_prompt, av.model_id, av.synthesis_model_id,
               av.runtime_config_json::text AS runtime_config_json,
               av.welcome_config_json::text AS welcome_config_json,
               av.routing_tags_json::text AS routing_tags_json,
               av.content_hash
        FROM agent_definition d
        JOIN agent_definition_version av ON av.agent_id = d.id AND av.status = 'published'
        WHERE d.status = 'active' AND d.del_flag = '0' AND d.is_default = TRUE
        ORDER BY d.sort_order DESC, d.id ASC, av.version_no DESC
        LIMIT 1
    """)
    EmbedAgentRuntimeRow selectDefaultAgentRuntime();

    /**
 * 获取Published智能体Runtimes。
 *
     * Returns the published runtime candidates used by first-turn automatic
     * routing. The conversation service still applies object authorization
     * before selecting a candidate; this query is only a bounded candidate
     * catalog and never grants access by itself.
     */
    @Select("""
        SELECT av.id AS agent_version_id, av.agent_id, d.agent_key,
               d.name AS agent_name, d.description AS agent_description, d.status AS agent_status,
               av.version_no, av.status AS version_status, av.published_at,
               av.system_prompt, av.model_id, av.synthesis_model_id,
               av.runtime_config_json::text AS runtime_config_json,
               av.welcome_config_json::text AS welcome_config_json,
               av.routing_tags_json::text AS routing_tags_json,
               av.content_hash
        FROM agent_definition d
        JOIN agent_definition_version av ON av.agent_id = d.id AND av.status = 'published'
        WHERE d.status = 'active' AND d.del_flag = '0'
        ORDER BY d.is_default DESC, d.sort_order DESC, d.name ASC, d.id ASC,
                 av.version_no DESC, av.id DESC
        LIMIT 100
        """)
    List<EmbedAgentRuntimeRow> selectPublishedAgentRuntimes();

    /**
     * 获取智能体运行时ByRoute令牌。
     *
     * @param routeToken route令牌参数
     * @return 处理结果
     */
    @Select("""
        SELECT av.id AS agent_version_id, av.agent_id, d.agent_key,
               d.name AS agent_name, d.description AS agent_description, d.status AS agent_status,
               av.version_no, av.status AS version_status, av.published_at,
               av.system_prompt, av.model_id, av.synthesis_model_id,
               av.runtime_config_json::text AS runtime_config_json,
               av.welcome_config_json::text AS welcome_config_json,
               av.routing_tags_json::text AS routing_tags_json,
               av.content_hash
        FROM agent_definition d
        JOIN agent_definition_version av ON av.agent_id = d.id AND av.status = 'published'
        WHERE d.status = 'active' AND d.del_flag = '0'
          AND (lower(d.agent_key) = lower(#{routeToken}) OR lower(d.name) = lower(#{routeToken}))
        ORDER BY CASE WHEN lower(d.agent_key) = lower(#{routeToken}) THEN 0 ELSE 1 END,
                 d.sort_order DESC, d.id ASC, av.version_no DESC
        LIMIT 1
        """)
    EmbedAgentRuntimeRow selectAgentRuntimeByRouteToken(@Param("routeToken") String routeToken);

    /**
     * 获取{@code Bindings}。
     *
     * @param agentVersionId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, 'tool' AS resource_type, resource_id, permission,
               config_json::text AS config_json
        FROM agent_agent_version_tool WHERE agent_version_id = #{agentVersionId}
        UNION ALL
        SELECT id, 'skill' AS resource_type, resource_id, permission,
               config_json::text AS config_json
        FROM agent_agent_version_skill WHERE agent_version_id = #{agentVersionId}
        UNION ALL
        SELECT id, 'knowledge_base' AS resource_type, resource_id, permission,
               config_json::text AS config_json
        FROM agent_agent_version_knowledge WHERE agent_version_id = #{agentVersionId}
        ORDER BY resource_type, resource_id
        """)
    List<AgentVersionBindingRow> selectBindings(@Param("agentVersionId") Long agentVersionId);

    /**
     * 处理countUnsafe嵌入式会话Tools并返回对应结果。
     *
     * @param agentVersionId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT count(*)
        FROM agent_agent_version_tool binding
        JOIN agent_tool tool ON tool.id = binding.resource_id AND tool.del_flag = '0'
        WHERE binding.agent_version_id = #{agentVersionId}
          AND (tool.risk_level IN ('R2', 'R3') OR tool.is_available = FALSE)
        """)
    int countUnsafeEmbedTools(@Param("agentVersionId") Long agentVersionId);

    /**
     * 获取UnsafeInteractive工具Ids。
     *
     * @param agentVersionId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT binding.resource_id
        FROM agent_agent_version_tool binding
        LEFT JOIN agent_tool tool ON tool.id = binding.resource_id AND tool.del_flag = '0'
        WHERE binding.agent_version_id = #{agentVersionId}
          AND (tool.id IS NULL OR tool.status <> 'active' OR tool.is_available = FALSE
               OR tool.risk_level IN ('R2', 'R3'))
        ORDER BY binding.resource_id
        """)
    List<Long> selectUnsafeInteractiveToolIds(@Param("agentVersionId") Long agentVersionId);

    /**
     * 创建并保存会话。
     *
     * @param conversation 会话参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_conversation (
            id, user_id, agent_id, agent_version_id, principal_type, title,
            visibility, status, session_key, create_by, create_time, del_flag,
            metadata_json, extra_json
        ) VALUES (
            #{id}, #{userId}, #{agentId}, #{agentVersionId}, #{principalType}, #{title},
            'private', 'active', #{sessionKey}, #{createBy}, #{createTime}, '0',
            '{}'::jsonb, '{}'::jsonb
        )
        """)
    int insertConversation(AgentConversation conversation);

    /**
     * 创建并保存会话。
     *
     * @param session 会话参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_embed_session (
            id, session_key, application_id, service_account_id, agent_version_id,
            conversation_id, external_user_hash, status, expires_at, created_at
        ) VALUES (
            #{id}, #{sessionKey}, #{applicationId}, #{serviceAccountId}, #{agentVersionId},
            #{conversationId}, #{externalUserHash}, #{status}, #{expiresAt}, #{createdAt}
        )
        """)
    int insertSession(EmbedSession session);

    /**
     * 处理close会话并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param applicationId 资源标识
     * @param serviceAccountId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_embed_session
        SET status = 'closed', last_used_at = #{now}
        WHERE id = #{sessionId} AND application_id = #{applicationId}
          AND service_account_id = #{serviceAccountId} AND status = 'active'
        """)
    int closeSession(
        @Param("sessionId") Long sessionId,
        @Param("applicationId") Long applicationId,
        @Param("serviceAccountId") Long serviceAccountId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理lock会话并返回对应结果。
     *
     * @param sessionId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, session_key, application_id, service_account_id, agent_version_id,
               conversation_id, external_user_hash, status, expires_at,
               last_used_at, created_at
        FROM agent_embed_session
        WHERE id = #{sessionId}
        FOR UPDATE
        """)
    EmbedSession lockSession(@Param("sessionId") Long sessionId);

    /**
     * 获取会话。
     *
     * @param sessionId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, session_key, application_id, service_account_id, agent_version_id,
               conversation_id, external_user_hash, status, expires_at,
               last_used_at, created_at
        FROM agent_embed_session
        WHERE id = #{sessionId}
        """)
    EmbedSession selectSession(@Param("sessionId") Long sessionId);

    /**
     * 将输入数据转换为uch会话。
     *
     * @param sessionId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_embed_session
        SET last_used_at = #{now}
        WHERE id = #{sessionId} AND status = 'active' AND expires_at > #{now}
        """)
    int touchSession(@Param("sessionId") Long sessionId, @Param("now") LocalDateTime now);

    /**
 * 将输入数据转换为uch会话Sliding。
 * Slides an active Embed session by its originally issued bounded duration. */
    @Update("""
        UPDATE agent_embed_session
        SET last_used_at = #{now},
            expires_at = #{now} + make_interval(mins => #{sessionMinutes})
        WHERE id = #{sessionId} AND status = 'active' AND expires_at > #{now}
        """)
    int touchSessionSliding(
        @Param("sessionId") Long sessionId,
        @Param("sessionMinutes") int sessionMinutes,
        @Param("now") LocalDateTime now
    );

    /**
     * 获取会话回合ByKey。
     *
     * @param sessionId 资源标识
     * @param idempotencyHash {@code idempotencyHash}参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, session_id, idempotency_hash, request_hash, trace_id,
               status, error_summary, stop_requested_at, execution_owner, heartbeat_at,
               started_at, finished_at
        FROM agent_embed_turn
        WHERE session_id = #{sessionId} AND idempotency_hash = #{idempotencyHash}
        """)
    EmbedTurn selectTurnByKey(
        @Param("sessionId") Long sessionId,
        @Param("idempotencyHash") String idempotencyHash
    );

    /**
     * 获取会话回合。
     *
     * @param sessionId 资源标识
     * @param turnId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, session_id, idempotency_hash, request_hash, trace_id,
               status, error_summary, stop_requested_at, execution_owner, heartbeat_at,
               started_at, finished_at
        FROM agent_embed_turn
        WHERE id = #{turnId} AND session_id = #{sessionId}
        """)
    EmbedTurn selectTurn(
        @Param("sessionId") Long sessionId,
        @Param("turnId") Long turnId
    );

    /**
     * 获取{@code Turns}。
     *
     * @param sessionId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, session_id, idempotency_hash, request_hash, trace_id,
               status, error_summary, stop_requested_at, execution_owner, heartbeat_at,
               started_at, finished_at
        FROM agent_embed_turn
        WHERE session_id = #{sessionId}
        ORDER BY started_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<EmbedTurn> selectTurns(
        @Param("sessionId") Long sessionId,
        @Param("limit") int limit
    );

    /**
     * 获取Active会话回合Ids。
     *
     * @param sessionId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id
        FROM agent_embed_turn
        WHERE session_id = #{sessionId} AND status IN ('running', 'stopping')
        ORDER BY id
        """)
    List<Long> selectActiveTurnIds(@Param("sessionId") Long sessionId);

    /**
     * 处理request会话Stops并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_embed_turn
        SET status = 'stopping', stop_requested_at = COALESCE(stop_requested_at, #{now})
        WHERE session_id = #{sessionId} AND status = 'running'
        """)
    int requestSessionStops(
        @Param("sessionId") Long sessionId,
        @Param("now") LocalDateTime now
    );

    /**
     * 创建并保存会话回合。
     *
     * @param turn 会话回合参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_embed_turn (
            id, session_id, idempotency_hash, request_hash, trace_id, status, started_at
        ) VALUES (
            #{id}, #{sessionId}, #{idempotencyHash}, #{requestHash}, #{traceId},
            #{status}, #{startedAt}
        )
        ON CONFLICT DO NOTHING
        """)
    int insertTurn(EmbedTurn turn);

    /**
     * 处理claim会话回合并返回对应结果。
     *
     * @param turnId 资源标识
     * @param owner {@code owner}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_embed_turn
        SET execution_owner = #{owner}, heartbeat_at = #{now}
        WHERE id = #{turnId} AND status = 'running' AND execution_owner IS NULL
        """)
    int claimTurn(
        @Param("turnId") Long turnId,
        @Param("owner") String owner,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理heartbeat会话回合并返回对应结果。
     *
     * @param turnId 资源标识
     * @param owner {@code owner}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_embed_turn
        SET heartbeat_at = #{now}
        WHERE id = #{turnId} AND execution_owner = #{owner}
          AND status IN ('running', 'stopping')
        """)
    int heartbeatTurn(
        @Param("turnId") Long turnId,
        @Param("owner") String owner,
        @Param("now") LocalDateTime now
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
     * 创建并保存用户消息。
     *
     * @param id 资源标识
     * @param conversationId 资源标识
     * @param sequenceNo 起始位置或序号
     * @param traceId 资源标识
     * @param content 待处理内容
     * @param contentJson 待处理内容
     * @param agentId 资源标识
     * @param agentVersionId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_conversation_message (
            id, conversation_id, seq_no, trace_id, role, content, content_json,
            agent_id, agent_version_id, status, created_at
        ) VALUES (
            #{id}, #{conversationId}, #{sequenceNo}, #{traceId}, 'user', #{content},
            CAST(#{contentJson} AS jsonb), #{agentId}, #{agentVersionId}, 'completed', #{now}
        )
        """)
    int insertUserMessage(
        @Param("id") Long id,
        @Param("conversationId") Long conversationId,
        @Param("sequenceNo") int sequenceNo,
        @Param("traceId") String traceId,
        @Param("content") String content,
        @Param("contentJson") String contentJson,
        @Param("agentId") Long agentId,
        @Param("agentVersionId") Long agentVersionId,
        @Param("now") LocalDateTime now
    );

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
     * 获取{@code Messages}。
     *
     * @param conversationId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT recent.id, recent.conversation_id, recent.sequence_no, recent.trace_id,
               recent.role, recent.content, recent.agent_id, recent.agent_version_id,
               recent.model_id, recent.status, feedback.rating AS feedback,
               recent.prompt_tokens, recent.completion_tokens, recent.total_tokens,
               recent.created_at
        FROM (
            SELECT id, conversation_id, seq_no AS sequence_no, trace_id, role, content,
                   agent_id, agent_version_id, model_id, status,
                   prompt_tokens, completion_tokens, total_tokens, created_at
            FROM agent_conversation_message
            WHERE conversation_id = #{conversationId}
            ORDER BY seq_no DESC, id DESC
            LIMIT #{limit}
        ) recent
        LEFT JOIN LATERAL (
            SELECT rating
            FROM agent_chat_feedback
            WHERE conversation_id = recent.conversation_id
              AND message_id = recent.id
            ORDER BY updated_at DESC, id DESC
            LIMIT 1
        ) feedback ON TRUE
        ORDER BY recent.sequence_no, recent.id
        """)
    List<ConversationMessageRow> selectMessages(
        @Param("conversationId") Long conversationId,
        @Param("limit") int limit
    );

    /**
     * 将输入数据转换为uch会话。
     *
     * @param conversationId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_conversation
        SET last_message_at = #{now}, update_time = #{now}
        WHERE id = #{conversationId} AND principal_type = 'service_account'
          AND status = 'active' AND del_flag = '0'
        """)
    int touchConversation(
        @Param("conversationId") Long conversationId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理finish会话回合并返回对应结果。
     *
     * @param turnId 资源标识
     * @param status 目标状态
     * @param error {@code error}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_embed_turn
        SET status = #{status}, error_summary = #{error}, finished_at = #{now},
            execution_owner = NULL, heartbeat_at = NULL
        WHERE id = #{turnId} AND status IN ('running', 'stopping')
        """)
    int finishTurn(
        @Param("turnId") Long turnId,
        @Param("status") String status,
        @Param("error") String error,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理finishOwned会话回合并返回对应结果。
     *
     * @param turnId 资源标识
     * @param owner {@code owner}参数
     * @param status 目标状态
     * @param error {@code error}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_embed_turn
        SET status = #{status}, error_summary = #{error}, finished_at = #{now},
            execution_owner = NULL, heartbeat_at = NULL
        WHERE id = #{turnId} AND execution_owner = #{owner}
          AND status IN ('running', 'stopping')
        """)
    int finishOwnedTurn(
        @Param("turnId") Long turnId,
        @Param("owner") String owner,
        @Param("status") String status,
        @Param("error") String error,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code finishStaleTurns}并返回对应结果。
     *
     * @param staleBefore {@code staleBefore}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_embed_turn
        SET status = CASE WHEN status = 'stopping' THEN 'cancelled' ELSE 'failed' END,
            error_summary = CASE
                WHEN status = 'stopping' THEN '停止请求已在执行节点失联后完成'
                ELSE 'Embed执行节点失联，回合已终止'
            END,
            finished_at = #{now}, execution_owner = NULL, heartbeat_at = NULL
        WHERE status IN ('running', 'stopping')
          AND (
            (execution_owner IS NULL AND started_at < #{staleBefore})
            OR heartbeat_at < #{staleBefore}
          )
        """)
    int finishStaleTurns(
        @Param("staleBefore") LocalDateTime staleBefore,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code requestStop}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param turnId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_embed_turn
        SET status = 'stopping', stop_requested_at = #{now}
        WHERE id = #{turnId} AND session_id = #{sessionId} AND status = 'running'
        """)
    int requestStop(
        @Param("sessionId") Long sessionId,
        @Param("turnId") Long turnId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code stopRequested}并返回对应结果。
     *
     * @param turnId 资源标识
     * @return 判断结果，{@code true} 表示条件成立
     */
    @Select("""
        SELECT status = 'stopping' OR stop_requested_at IS NOT NULL
        FROM agent_embed_turn
        WHERE id = #{turnId}
        """)
    Boolean stopRequested(@Param("turnId") Long turnId);

    /**
     * 获取会话回合Events。
     *
     * @param conversationId 资源标识
     * @param traceId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, event_id, trace_id, conversation_id, run_id, step_id, cursor,
               parent_event_id, event_type, event_status, summary,
               payload_json::text AS payload_json,
               query_projection_json::text AS query_projection_json, sensitive_level,
               occurred_at, created_at
        FROM agent_execution_event
        WHERE conversation_id = #{conversationId} AND trace_id = #{traceId}
        ORDER BY cursor, id
        """)
    List<AgentExecutionEvent> selectTurnEvents(
        @Param("conversationId") Long conversationId,
        @Param("traceId") String traceId
    );

    /**
     * 获取会话回合EventsAfter。
     *
     * @param conversationId 资源标识
     * @param traceId 资源标识
     * @param afterCursor {@code afterCursor}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, event_id, trace_id, conversation_id, run_id, step_id, cursor,
               parent_event_id, event_type, event_status, summary,
               payload_json::text AS payload_json,
               query_projection_json::text AS query_projection_json, sensitive_level,
               occurred_at, created_at
        FROM agent_execution_event
        WHERE conversation_id = #{conversationId} AND trace_id = #{traceId}
          AND cursor > #{afterCursor}
        ORDER BY cursor, id
        LIMIT #{limit}
        """)
    List<AgentExecutionEvent> selectTurnEventsAfter(
        @Param("conversationId") Long conversationId,
        @Param("traceId") String traceId,
        @Param("afterCursor") long afterCursor,
        @Param("limit") int limit
    );

    /**
     * 创建并保存浏览器凭据。
     *
     * @param credential 凭据参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_embed_browser_credential (
            id, token_hash, token_kind, application_id, api_credential_id,
            service_account_id, agent_version_id, host_origin, external_user_hash,
            session_minutes, session_id, expires_at, created_at
        ) VALUES (
            #{id}, #{tokenHash}, #{tokenKind}, #{applicationId}, #{apiCredentialId},
            #{serviceAccountId}, #{agentVersionId}, #{hostOrigin}, #{externalUserHash},
            #{sessionMinutes}, #{sessionId}, #{expiresAt}, #{createdAt}
        ) ON CONFLICT DO NOTHING
        """)
    int insertBrowserCredential(EmbedBrowserCredential credential);

    /**
     * 获取浏览器凭据。
     *
     * @param tokenHash 令牌Hash参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, token_hash, token_kind, application_id, api_credential_id,
               service_account_id, agent_version_id, host_origin, external_user_hash,
               session_minutes, session_id, expires_at, consumed_at, revoked_at,
               last_used_at, created_at
        FROM agent_embed_browser_credential
        WHERE token_hash = #{tokenHash}
        """)
    EmbedBrowserCredential selectBrowserCredential(@Param("tokenHash") String tokenHash);

    /**
     * 将输入数据转换为uch浏览器凭据。
     *
     * @param credentialId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_embed_browser_credential
        SET last_used_at = #{now}
        WHERE id = #{credentialId} AND revoked_at IS NULL AND expires_at > #{now}
        """)
    int touchBrowserCredential(
        @Param("credentialId") Long credentialId,
        @Param("now") LocalDateTime now
    );

    /**
 * 将输入数据转换为uch浏览器凭据Sliding。
 * Slides only a bound session credential; launch credentials remain one-shot. */
    @Update("""
        UPDATE agent_embed_browser_credential
        SET last_used_at = #{now},
            expires_at = #{now} + make_interval(mins => #{sessionMinutes})
        WHERE id = #{credentialId} AND token_kind = 'session'
          AND revoked_at IS NULL AND expires_at > #{now}
        """)
    int touchBrowserCredentialSliding(
        @Param("credentialId") Long credentialId,
        @Param("sessionMinutes") int sessionMinutes,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理consumeLaunch凭据并返回对应结果。
     *
     * @param credentialId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_embed_browser_credential
        SET consumed_at = #{now}, revoked_at = #{now}
        WHERE id = #{credentialId} AND token_kind = 'launch'
          AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > #{now}
        """)
    int consumeLaunchCredential(
        @Param("credentialId") Long credentialId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理revoke浏览器凭据并返回对应结果。
     *
     * @param credentialId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_embed_browser_credential
        SET revoked_at = #{now}
        WHERE id = #{credentialId} AND revoked_at IS NULL
        """)
    int revokeBrowserCredential(
        @Param("credentialId") Long credentialId,
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
            #{id}, #{conversationId}, #{userId}, #{originalName}, #{storageType},
            #{storageRef}, #{mimeType}, #{sizeBytes}, #{sha256}, #{status}, #{createdAt}
        )
        """)
    int insertAttachment(AgentConversationAttachment attachment);

    /**
     * 获取附件。
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
          AND user_id = #{userId} AND status = 'ready'
        """)
    AgentConversationAttachment selectAttachment(
        @Param("conversationId") Long conversationId,
        @Param("attachmentId") Long attachmentId,
        @Param("userId") Long userId
    );

    /**
     * 处理attach文件并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param attachmentId 资源标识
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
    int attachFile(
        @Param("conversationId") Long conversationId,
        @Param("attachmentId") Long attachmentId,
        @Param("userId") Long userId,
        @Param("turnId") Long turnId
    );
}
