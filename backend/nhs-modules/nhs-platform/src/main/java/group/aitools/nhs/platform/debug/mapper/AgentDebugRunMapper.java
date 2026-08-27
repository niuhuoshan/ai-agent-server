package group.aitools.nhs.platform.debug.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import group.aitools.nhs.platform.debug.persistence.row.AgentDebugRunRow;

import java.util.List;

/**
 * 创建并保存{@code insert}。
 *
 * 定义智能体DebugRun相关的数据访问契约。
 * Owner-scoped persistence for durable Agent debugging attempts. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AgentDebugRunMapper {

    @Insert("""
        INSERT INTO agent_debug_run (
            id, owner_id, idempotency_key, agent_id, agent_version_id,
            task_id, run_id, parent_debug_run_id, input_text, input_sha256, created_at
        ) VALUES (
            #{id}, #{ownerId}, #{idempotencyKey}, #{agentId}, #{agentVersionId},
            #{taskId}, #{runId}, #{parentDebugRunId}, #{inputText}, #{inputSha256}, #{createdAt}
        )
        """)
    int insert(AgentDebugRunRow row);

    /**
     * 获取{@code Owned}。
     *
     * @param debugRunId 资源标识
     * @param ownerId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, owner_id, idempotency_key, agent_id, agent_version_id,
               task_id, run_id, parent_debug_run_id, input_text, input_sha256, created_at
        FROM agent_debug_run
        WHERE id = #{debugRunId} AND owner_id = #{ownerId}
        """)
    AgentDebugRunRow selectOwned(
        @Param("debugRunId") Long debugRunId,
        @Param("ownerId") Long ownerId
    );

    /**
     * 获取{@code OwnedByIdempotencyKey}。
     *
     * @param ownerId 资源标识
     * @param idempotencyKey {@code idempotencyKey}参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, owner_id, idempotency_key, agent_id, agent_version_id,
               task_id, run_id, parent_debug_run_id, input_text, input_sha256, created_at
        FROM agent_debug_run
        WHERE owner_id = #{ownerId} AND idempotency_key = #{idempotencyKey}
        """)
    AgentDebugRunRow selectOwnedByIdempotencyKey(
        @Param("ownerId") Long ownerId,
        @Param("idempotencyKey") String idempotencyKey
    );

    /**
     * 获取{@code OwnedList}。
     *
     * @param ownerId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, owner_id, idempotency_key, agent_id, agent_version_id,
               task_id, run_id, parent_debug_run_id, input_text, input_sha256, created_at
        FROM agent_debug_run
        WHERE owner_id = #{ownerId}
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentDebugRunRow> selectOwnedList(
        @Param("ownerId") Long ownerId,
        @Param("limit") int limit
    );
}
