package group.aitools.nhs.platform.execution.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.execution.domain.AgentExecutionEvent;

import java.util.List;

/**
 * 定义智能体执行事件相关的数据访问契约。
 */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AgentExecutionEventMapper {

    /**
 * 处理{@code redactTextDeltasForRetraction}并返回对应结果。
 *
     * Replaces already-streamed text fragments after a runtime emits a
     * withdrawal/retraction event.  Streaming can discover a secret only
     * after several delta events have been persisted, so the prior rows must
     * be scrubbed instead of relying on readers to remember the retraction.
     */
    @Update("""
        UPDATE agent_execution_event
        SET summary = '[内容已撤回]',
            payload_json = CAST('{"redacted":true,"retracted":true}' AS jsonb),
            query_projection_json = CAST('{}' AS jsonb),
            sensitive_level = 'internal'
        WHERE trace_id = #{traceId}
          AND event_type = 'text_delta'
        """)
    int redactTextDeltasForRetraction(@Param("traceId") String traceId);

    /**
     * 处理{@code nextCursor}并返回对应结果。
     *
     * @return 处理结果
     */
    @Select("SELECT nextval('agent_execution_event_cursor_seq')")
    Long nextCursor();

    /**
     * 创建并保存事件。
     *
     * @param event 事件参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_execution_event (
            id, event_id, trace_id, conversation_id, run_id, step_id, cursor,
            event_type, event_status, summary, payload_json, query_projection_json, sensitive_level,
            occurred_at, created_at
        ) VALUES (
            #{id}, #{eventId}, #{traceId}, #{conversationId}, #{runId}, #{stepId}, #{cursor},
            #{eventType}, #{eventStatus}, #{summary}, CAST(#{payloadJson} AS jsonb),
            CAST(#{queryProjectionJson} AS jsonb), #{sensitiveLevel}, #{occurredAt}, #{createdAt}
        )
        ON CONFLICT (event_id) DO NOTHING
        """)
    int insertEvent(AgentExecutionEvent event);

    /**
     * 获取By事件Id。
     *
     * @param eventId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT e.id, e.event_id, e.trace_id, e.conversation_id, e.run_id, e.step_id, e.cursor,
               e.event_type, e.event_status, e.summary, CAST(e.payload_json AS text) AS payload_json,
               CAST(CASE WHEN a.id IS NULL THEN e.query_projection_json
                    ELSE COALESCE(e.query_projection_json, '{}'::jsonb)
                         || jsonb_build_object('permissionRequestId', a.id)
                    END AS text) AS query_projection_json,
               e.sensitive_level, e.occurred_at, e.created_at
        FROM agent_execution_event e
        LEFT JOIN agent_approval_request a ON a.request_event_id = e.event_id
        WHERE e.event_id = #{eventId}
        """)
    AgentExecutionEvent selectByEventId(@Param("eventId") String eventId);

    /**
     * 获取会话Events。
     *
     * @param conversationId 资源标识
     * @param afterCursor {@code afterCursor}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT e.id, e.event_id, e.trace_id, e.conversation_id, e.run_id, e.step_id, e.cursor,
               e.event_type, e.event_status, e.summary, CAST(e.payload_json AS text) AS payload_json,
               CAST(CASE WHEN a.id IS NULL THEN e.query_projection_json
                    ELSE COALESCE(e.query_projection_json, '{}'::jsonb)
                         || jsonb_build_object('permissionRequestId', a.id)
                    END AS text) AS query_projection_json,
               e.sensitive_level, e.occurred_at, e.created_at
        FROM agent_execution_event e
        LEFT JOIN agent_approval_request a ON a.request_event_id = e.event_id
        WHERE e.conversation_id = #{conversationId}
          AND e.cursor > #{afterCursor}
        ORDER BY e.cursor
        LIMIT #{limit}
        """)
    List<AgentExecutionEvent> selectConversationEvents(
        @Param("conversationId") Long conversationId,
        @Param("afterCursor") long afterCursor,
        @Param("limit") int limit
    );

    /**
     * 获取会话链路追踪Events。
     *
     * @param traceId 资源标识
     * @param conversationId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT e.id, e.event_id, e.trace_id, e.conversation_id, e.run_id, e.step_id, e.cursor,
               e.event_type, e.event_status, e.summary, CAST(e.payload_json AS text) AS payload_json,
               CAST(CASE WHEN a.id IS NULL THEN e.query_projection_json
                    ELSE COALESCE(e.query_projection_json, '{}'::jsonb)
                         || jsonb_build_object('permissionRequestId', a.id)
                    END AS text) AS query_projection_json,
               e.sensitive_level, e.occurred_at, e.created_at
        FROM agent_execution_event e
        LEFT JOIN agent_approval_request a ON a.request_event_id = e.event_id
        WHERE e.trace_id = #{traceId} AND e.conversation_id = #{conversationId}
        ORDER BY e.cursor, e.id
        LIMIT #{limit}
        """)
    List<AgentExecutionEvent> selectConversationTraceEvents(
        @Param("traceId") String traceId,
        @Param("conversationId") Long conversationId,
        @Param("limit") int limit
    );

    /**
     * 获取会话链路追踪EventsAfter。
     *
     * @param traceId 资源标识
     * @param conversationId 资源标识
     * @param afterCursor {@code afterCursor}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT e.id, e.event_id, e.trace_id, e.conversation_id, e.run_id, e.step_id, e.cursor,
               e.event_type, e.event_status, e.summary, CAST(e.payload_json AS text) AS payload_json,
               CAST(CASE WHEN a.id IS NULL THEN e.query_projection_json
                    ELSE COALESCE(e.query_projection_json, '{}'::jsonb)
                         || jsonb_build_object('permissionRequestId', a.id)
                    END AS text) AS query_projection_json,
               e.sensitive_level, e.occurred_at, e.created_at
        FROM agent_execution_event e
        LEFT JOIN agent_approval_request a ON a.request_event_id = e.event_id
        WHERE e.trace_id = #{traceId}
          AND e.conversation_id = #{conversationId}
          AND e.cursor > #{afterCursor}
        ORDER BY e.cursor, e.id
        LIMIT #{limit}
        """)
    List<AgentExecutionEvent> selectConversationTraceEventsAfter(
        @Param("traceId") String traceId,
        @Param("conversationId") Long conversationId,
        @Param("afterCursor") long afterCursor,
        @Param("limit") int limit
    );

    /**
     * 获取任务RunEvents。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param afterCursor {@code afterCursor}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT e.id, e.event_id, e.trace_id, e.conversation_id, e.run_id, e.step_id,
               e.cursor, e.event_type, e.event_status, e.summary,
               CAST(e.payload_json AS text) AS payload_json,
               CAST(CASE WHEN a.id IS NULL THEN e.query_projection_json
                    ELSE COALESCE(e.query_projection_json, '{}'::jsonb)
                         || jsonb_build_object('permissionRequestId', a.id)
                    END AS text) AS query_projection_json,
               e.sensitive_level, e.occurred_at, e.created_at
        FROM agent_execution_event e
        INNER JOIN agent_task_run r ON r.id = e.run_id
        LEFT JOIN agent_approval_request a ON a.request_event_id = e.event_id
        WHERE r.task_id = #{taskId}
          AND r.id = #{runId}
          AND e.cursor > #{afterCursor}
        ORDER BY e.cursor
        LIMIT #{limit}
        """)
    List<AgentExecutionEvent> selectTaskRunEvents(
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("afterCursor") long afterCursor,
        @Param("limit") int limit
    );

    /**
     * 获取任务IdForRun。
     *
     * @param runId 资源标识
     * @return 处理结果
     */
    @Select("SELECT task_id FROM agent_task_run WHERE id = #{runId}")
    Long selectTaskIdForRun(@Param("runId") Long runId);

    /**
 * 获取External执行事件。
 * Finds the server-owned external execution request for its creator. */
    @Select("""
        SELECT e.id, e.event_id, e.trace_id, e.conversation_id, e.run_id, e.step_id,
               e.cursor, e.event_type, e.event_status, e.summary,
               CAST(e.payload_json AS text) AS payload_json,
               CAST(e.query_projection_json AS text) AS query_projection_json,
               e.sensitive_level, e.occurred_at, e.created_at
        FROM agent_execution_event e
        INNER JOIN agent_task_run r ON r.id = e.run_id
        WHERE e.event_type = 'external_execution_required'
          AND r.created_by = #{userId}
          AND e.payload_json ->> 'replyId' = #{replyId}
        ORDER BY e.cursor DESC, e.id DESC
        LIMIT 1
        """)
    AgentExecutionEvent selectExternalExecutionEvent(
        @Param("replyId") String replyId,
        @Param("userId") Long userId
    );
}
