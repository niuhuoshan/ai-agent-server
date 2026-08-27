package group.aitools.nhs.platform.sandbox.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.sandbox.persistence.row.ExternalExecutionResumeRow;

import java.time.LocalDateTime;

/**
 * 获取{@code ForUpdate}。
 *
 * 定义External执行Resume相关的数据访问契约。
 * Persistence boundary for cross-JVM external-execution idempotency. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface ExternalExecutionResumeMapper {

    @Select("""
        SELECT id, user_id, reply_id, task_id, run_id, step_id, trace_id,
               results_hash, results_json::text AS results_json, status,
               created_at, dispatched_at
        FROM agent_external_execution_resume
        WHERE user_id = #{userId} AND reply_id = #{replyId}
        FOR UPDATE
        """)
    ExternalExecutionResumeRow selectForUpdate(
        @Param("userId") Long userId,
        @Param("replyId") String replyId
    );

    /**
     * 创建并保存{@code Pending}。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_external_execution_resume (
            id, user_id, reply_id, task_id, run_id, step_id, trace_id,
            results_hash, results_json, status, created_at
        ) VALUES (
            #{id}, #{userId}, #{replyId}, #{taskId}, #{runId}, #{stepId}, #{traceId},
            #{resultsHash}, CAST(#{resultsJson} AS jsonb), 'pending', #{createdAt}
        )
        ON CONFLICT (user_id, reply_id) DO NOTHING
        """)
    int insertPending(ExternalExecutionResumeRow row);

    /**
     * 处理{@code markDispatched}并返回对应结果。
     *
     * @param userId 资源标识
     * @param replyId 资源标识
     * @param dispatchedAt {@code dispatchedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_external_execution_resume
        SET status = 'dispatched', dispatched_at = #{dispatchedAt}
        WHERE user_id = #{userId} AND reply_id = #{replyId} AND status = 'pending'
        """)
    int markDispatched(
        @Param("userId") Long userId,
        @Param("replyId") String replyId,
        @Param("dispatchedAt") LocalDateTime dispatchedAt
    );
}
