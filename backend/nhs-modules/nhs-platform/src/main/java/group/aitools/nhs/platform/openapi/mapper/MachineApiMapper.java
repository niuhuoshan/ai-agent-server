package group.aitools.nhs.platform.openapi.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 定义Machine接口相关的数据访问契约。
 */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface MachineApiMapper {

    /**
     * 处理{@code consumeRate}并返回对应结果。
     *
     * @param applicationId 资源标识
     * @param windowStart {@code windowStart}参数
     * @param maximum {@code maximum}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Select(value = """
        INSERT INTO agent_api_rate_bucket (
            application_id, window_start, request_count, updated_at
        ) VALUES (#{applicationId}, #{windowStart}, 1, #{now})
        ON CONFLICT (application_id, window_start) DO UPDATE
        SET request_count = agent_api_rate_bucket.request_count + 1,
            updated_at = EXCLUDED.updated_at
        WHERE agent_api_rate_bucket.request_count < #{maximum}
        RETURNING request_count
        """, affectData = true)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Integer consumeRate(
        @Param("applicationId") Long applicationId,
        @Param("windowStart") LocalDateTime windowStart,
        @Param("maximum") int maximum,
        @Param("now") LocalDateTime now
    );

    /**
     * 创建并保存{@code Call}。
     *
     * @param id 资源标识
     * @param requestId 资源标识
     * @param applicationId 资源标识
     * @param credentialId 资源标识
     * @param serviceAccountId 资源标识
     * @param endpointKey {@code endpointKey}参数
     * @param httpMethod {@code httpMethod}参数
     * @param requiredScope required范围参数
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param requestBytes {@code requestBytes}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_api_call (
            id, request_id, application_id, credential_id, service_account_id,
            endpoint_key, http_method, required_scope, resource_type, resource_id,
            outcome, request_bytes, created_at
        ) VALUES (
            #{id}, #{requestId}, #{applicationId}, #{credentialId}, #{serviceAccountId},
            #{endpointKey}, #{httpMethod}, #{requiredScope}, #{resourceType}, #{resourceId},
            'accepted', #{requestBytes}, #{now}
        )
        """)
    int insertCall(
        @Param("id") Long id,
        @Param("requestId") String requestId,
        @Param("applicationId") Long applicationId,
        @Param("credentialId") Long credentialId,
        @Param("serviceAccountId") Long serviceAccountId,
        @Param("endpointKey") String endpointKey,
        @Param("httpMethod") String httpMethod,
        @Param("requiredScope") String requiredScope,
        @Param("resourceType") String resourceType,
        @Param("resourceId") Long resourceId,
        @Param("requestBytes") int requestBytes,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code completeCall}并返回对应结果。
     *
     * @param callId 资源标识
     * @param outcome {@code outcome}参数
     * @param statusCode 目标状态
     * @param durationMs {@code durationMs}参数
     * @param errorCode {@code errorCode}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_api_call
        SET outcome = #{outcome}, status_code = #{statusCode}, duration_ms = #{durationMs},
            error_code = #{errorCode}, completed_at = #{now}
        WHERE id = #{callId} AND outcome = 'accepted'
        """)
    int completeCall(
        @Param("callId") Long callId,
        @Param("outcome") String outcome,
        @Param("statusCode") int statusCode,
        @Param("durationMs") long durationMs,
        @Param("errorCode") String errorCode,
        @Param("now") LocalDateTime now
    );
}
