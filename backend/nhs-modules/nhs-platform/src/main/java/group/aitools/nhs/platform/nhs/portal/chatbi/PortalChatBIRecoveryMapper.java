package group.aitools.nhs.platform.nhs.portal.chatbi;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.data.domain.AgentDataQuery;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取LatestOwned查询By链路追踪。
 *
 * 定义门户对话BIRecovery相关的数据访问契约。
 * Persistence boundary for ChatBI SQL repair and task-plan state. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
@Mapper
public interface PortalChatBIRecoveryMapper {

    @Select("""
        SELECT id, task_id, run_id, conversation_id, trace_id, data_source_id, dataset_id,
               data_source_revision, dataset_revision, user_query,
               sql_plan_json::text AS sql_plan_json, sql_text, sql_hash,
               permission_summary_json::text AS permission_summary_json,
               row_count, result_bytes, result_truncated, status, error_summary,
               started_at, finished_at, created_by, created_at
        FROM agent_data_query
        WHERE trace_id = #{traceId} AND created_by = #{userId}
        ORDER BY created_at DESC, id DESC
        LIMIT 1
        """)
    AgentDataQuery selectLatestOwnedQueryByTrace(
        @Param("traceId") String traceId,
        @Param("userId") Long userId
    );

    /**
     * 创建并保存{@code RepairAttempt}。
     *
     * @param attempt {@code attempt}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_chatbi_sql_repair_attempt (
            id, owner_id, conversation_id, dataset_id, trace_id, failed_query_id,
            attempt_no, max_attempts, error_category, error_summary, failed_sql,
            status, created_at
        ) VALUES (
            #{id}, #{ownerId}, #{conversationId}, #{datasetId}, #{traceId}, #{failedQueryId},
            #{attemptNo}, #{maxAttempts}, #{errorCategory}, #{errorSummary}, #{failedSql},
            #{status}, #{createdAt}
        )
        """)
    int insertRepairAttempt(AgentChatBISqlRepairAttempt attempt);

    /**
     * 处理{@code markRepairExecuting}并返回对应结果。
     *
     * @param attempt {@code attempt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_sql_repair_attempt
        SET repaired_sql = #{repairedSql}, repair_model_id = #{repairModelId},
            repair_reason = #{repairReason}, status = 'executing'
        WHERE id = #{id} AND owner_id = #{ownerId} AND status = 'planning'
        """)
    int markRepairExecuting(AgentChatBISqlRepairAttempt attempt);

    /**
     * 处理{@code finishRepairAttempt}并返回对应结果。
     *
     * @param attempt {@code attempt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_sql_repair_attempt
        SET retry_query_id = #{retryQueryId}, status = #{status},
            error_summary = #{errorSummary}, finished_at = #{finishedAt}
        WHERE id = #{id} AND owner_id = #{ownerId}
          AND status IN ('planning', 'executing')
        """)
    int finishRepairAttempt(AgentChatBISqlRepairAttempt attempt);

    /**
     * 获取{@code OwnedRepairAttempts}。
     *
     * @param traceId 资源标识
     * @param userId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, owner_id, conversation_id, dataset_id, trace_id, failed_query_id,
               retry_query_id, attempt_no, max_attempts, error_category, error_summary,
               failed_sql, repaired_sql, repair_model_id, repair_reason, status,
               created_at, finished_at
        FROM agent_chatbi_sql_repair_attempt
        WHERE trace_id = #{traceId} AND owner_id = #{userId}
        ORDER BY attempt_no, id
        """)
    List<AgentChatBISqlRepairAttempt> selectOwnedRepairAttempts(
        @Param("traceId") String traceId,
        @Param("userId") Long userId
    );

    /**
     * 创建并保存任务Plan。
     *
     * @param plan {@code plan}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_chatbi_task_plan (
            id, plan_key, owner_id, conversation_id, dataset_id, request_question,
            status, task_count, current_task_key, created_at, started_at, finished_at
        ) VALUES (
            #{id}, #{planKey}, #{ownerId}, #{conversationId}, #{datasetId}, #{requestQuestion},
            #{status}, #{taskCount}, #{currentTaskKey}, #{createdAt}, #{startedAt}, #{finishedAt}
        )
        """)
    int insertTaskPlan(AgentChatBITaskPlan plan);

    /**
     * 创建并保存任务PlanItem。
     *
     * @param item {@code item}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_chatbi_task_plan_item (
            id, plan_id, task_key, sequence_no, operation, query_text,
            depends_on_json, status, trace_id, result_query_id, error_summary,
            started_at, finished_at, created_at
        ) VALUES (
            #{id}, #{planId}, #{taskKey}, #{sequenceNo}, #{operation}, #{queryText},
            CAST(#{dependsOnJson} AS jsonb), #{status}, #{traceId}, #{resultQueryId},
            #{errorSummary}, #{startedAt}, #{finishedAt}, #{createdAt}
        )
        """)
    int insertTaskPlanItem(AgentChatBITaskPlanItem item);

    /**
     * 处理{@code markPlanRunning}并返回对应结果。
     *
     * @param planId 资源标识
     * @param ownerId 资源标识
     * @param taskKey 任务Key参数
     * @param startedAt {@code startedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_task_plan
        SET status = 'running', current_task_key = #{taskKey},
            started_at = COALESCE(started_at, #{startedAt})
        WHERE id = #{planId} AND owner_id = #{ownerId}
          AND status IN ('pending', 'running')
        """)
    int markPlanRunning(
        @Param("planId") Long planId,
        @Param("ownerId") Long ownerId,
        @Param("taskKey") String taskKey,
        @Param("startedAt") LocalDateTime startedAt
    );

    /**
     * 处理bindPlan会话并返回对应结果。
     *
     * @param planId 资源标识
     * @param ownerId 资源标识
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_task_plan
        SET conversation_id = #{conversationId}
        WHERE id = #{planId} AND owner_id = #{ownerId}
          AND (conversation_id IS NULL OR conversation_id = #{conversationId})
        """)
    int bindPlanConversation(
        @Param("planId") Long planId,
        @Param("ownerId") Long ownerId,
        @Param("conversationId") Long conversationId
    );

    /**
     * 处理finish任务Plan并返回对应结果。
     *
     * @param planId 资源标识
     * @param ownerId 资源标识
     * @param status 目标状态
     * @param finishedAt {@code finishedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_task_plan
        SET status = #{status}, current_task_key = NULL, finished_at = #{finishedAt}
        WHERE id = #{planId} AND owner_id = #{ownerId}
          AND status IN ('pending', 'running')
        """)
    int finishTaskPlan(
        @Param("planId") Long planId,
        @Param("ownerId") Long ownerId,
        @Param("status") String status,
        @Param("finishedAt") LocalDateTime finishedAt
    );

    /**
     * 处理mark任务Running并返回对应结果。
     *
     * @param planId 资源标识
     * @param taskKey 任务Key参数
     * @param traceId 资源标识
     * @param startedAt {@code startedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_task_plan_item
        SET status = 'running', trace_id = #{traceId}, started_at = #{startedAt}
        WHERE plan_id = #{planId} AND task_key = #{taskKey} AND status = 'pending'
        """)
    int markTaskRunning(
        @Param("planId") Long planId,
        @Param("taskKey") String taskKey,
        @Param("traceId") String traceId,
        @Param("startedAt") LocalDateTime startedAt
    );

    /**
     * 处理finish任务并返回对应结果。
     *
     * @param planId 资源标识
     * @param taskKey 任务Key参数
     * @param status 目标状态
     * @param resultQueryId 资源标识
     * @param errorSummary {@code errorSummary}参数
     * @param finishedAt {@code finishedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_task_plan_item
        SET status = #{status}, result_query_id = #{resultQueryId},
            error_summary = #{errorSummary}, finished_at = #{finishedAt}
        WHERE plan_id = #{planId} AND task_key = #{taskKey}
          AND status IN ('pending', 'running')
        """)
    int finishTask(
        @Param("planId") Long planId,
        @Param("taskKey") String taskKey,
        @Param("status") String status,
        @Param("resultQueryId") Long resultQueryId,
        @Param("errorSummary") String errorSummary,
        @Param("finishedAt") LocalDateTime finishedAt
    );

    /**
     * 获取Owned任务Plan。
     *
     * @param planKey {@code planKey}参数
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, plan_key, owner_id, conversation_id, dataset_id, request_question,
               status, task_count, current_task_key, created_at, started_at, finished_at
        FROM agent_chatbi_task_plan
        WHERE plan_key = #{planKey} AND owner_id = #{userId}
        """)
    AgentChatBITaskPlan selectOwnedTaskPlan(
        @Param("planKey") String planKey,
        @Param("userId") Long userId
    );

    /**
     * 获取Owned任务PlanBy结果。
     *
     * @param queryId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT p.id, p.plan_key, p.owner_id, p.conversation_id, p.dataset_id,
               p.request_question, p.status, p.task_count, p.current_task_key,
               p.created_at, p.started_at, p.finished_at
        FROM agent_chatbi_task_plan p
        INNER JOIN agent_chatbi_task_plan_item i ON i.plan_id = p.id
        WHERE i.result_query_id = #{queryId} AND p.owner_id = #{userId}
        ORDER BY p.created_at DESC, p.id DESC
        LIMIT 1
        """)
    AgentChatBITaskPlan selectOwnedTaskPlanByResult(
        @Param("queryId") Long queryId,
        @Param("userId") Long userId
    );

    /**
     * 获取任务PlanItems。
     *
     * @param planId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, plan_id, task_key, sequence_no, operation, query_text,
               depends_on_json::text AS depends_on_json, status, trace_id,
               result_query_id, error_summary, started_at, finished_at, created_at
        FROM agent_chatbi_task_plan_item
        WHERE plan_id = #{planId}
        ORDER BY sequence_no, id
        """)
    List<AgentChatBITaskPlanItem> selectTaskPlanItems(@Param("planId") Long planId);

    /**
     * 创建并保存任务Plan事件。
     *
     * @param event 事件参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_chatbi_task_plan_event (
            id, plan_id, owner_id, event_type, payload_json, created_at
        ) VALUES (
            #{id}, #{planId}, #{ownerId}, #{eventType}, CAST(#{payloadJson} AS jsonb), #{createdAt}
        )
        """)
    int insertTaskPlanEvent(AgentChatBITaskPlanEvent event);

    /**
     * 获取Owned任务PlanEvents。
     *
     * @param planId 资源标识
     * @param ownerId 资源标识
     * @param afterCursor {@code afterCursor}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, plan_id, owner_id, cursor, event_type,
               payload_json::text AS payload_json, created_at
        FROM agent_chatbi_task_plan_event
        WHERE plan_id = #{planId} AND owner_id = #{ownerId} AND cursor > #{afterCursor}
        ORDER BY cursor
        LIMIT #{limit}
        """)
    List<AgentChatBITaskPlanEvent> selectOwnedTaskPlanEvents(
        @Param("planId") Long planId,
        @Param("ownerId") Long ownerId,
        @Param("afterCursor") Long afterCursor,
        @Param("limit") int limit
    );

    /**
     * 判断More任务PlanEvents是否满足要求。
     *
     * @param planId 资源标识
     * @param ownerId 资源标识
     * @param afterCursor {@code afterCursor}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @Select("""
        SELECT 1
        FROM agent_chatbi_task_plan_event
        WHERE plan_id = #{planId} AND owner_id = #{ownerId} AND cursor > #{afterCursor}
        ORDER BY cursor
        LIMIT 1 OFFSET #{limit}
        """)
    Integer hasMoreTaskPlanEvents(
        @Param("planId") Long planId,
        @Param("ownerId") Long ownerId,
        @Param("afterCursor") Long afterCursor,
        @Param("limit") int limit
    );
}
