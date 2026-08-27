package group.aitools.nhs.platform.openapi.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.identity.service.ApiCredentialAuthenticator;
import group.aitools.nhs.platform.identity.service.AuthenticatedServiceAccount;
import group.aitools.nhs.platform.openapi.mapper.MachineApiMapper;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 负责Machine接口Gateway相关的业务编排与领域规则处理。
 */
@Service
public class MachineApiGatewayService {

    private final ApiCredentialAuthenticator authenticator;
    private final MachineApiMapper mapper;
    private final PlatformIdGenerator idGenerator;
    private final int requestsPerMinute;

    /**
     * 创建 {@code MachineApiGatewayService} 实例并初始化所需依赖。
     *
     * @param authenticator {@code authenticator}参数
     * @param mapper {@code mapper}参数
     * @param idGenerator {@code idGenerator}参数
     * @param requestsPerMinute {@code requestsPerMinute}参数
     */
    public MachineApiGatewayService(
        ApiCredentialAuthenticator authenticator,
        MachineApiMapper mapper,
        PlatformIdGenerator idGenerator,
        @Value("${agent.platform.api.rate-limit-per-minute:60}") int requestsPerMinute
    ) {
        this.authenticator = authenticator;
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.requestsPerMinute = Math.max(1, Math.min(requestsPerMinute, 10_000));
    }

    /**
     * 处理{@code begin}并返回对应结果。
     *
     * @param authorization 授权参数
     * @param allowedApplicationTypes 业务类型
     * @param requiredScope required范围参数
     * @param endpointKey {@code endpointKey}参数
     * @param method {@code method}参数
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param requestBytes {@code requestBytes}参数
     * @return 处理结果
     */
    public ApiCallContext begin(
        String authorization,
        Set<String> allowedApplicationTypes,
        String requiredScope,
        String endpointKey,
        String method,
        String resourceType,
        Long resourceId,
        long requestBytes
    ) {
        String secret = bearerSecret(authorization);
        AuthenticatedServiceAccount authenticated = authenticator.authenticate(secret);
        return beginAuthenticated(
            authenticated, allowedApplicationTypes, requiredScope, endpointKey,
            method, resourceType, resourceId, requestBytes
        );
    }

    /**
 * 处理{@code beginAuthenticated}并返回对应结果。
 * Audits an already revalidated short-lived capability against the same scope and rate limits. */
    public ApiCallContext beginAuthenticated(
        AuthenticatedServiceAccount authenticated,
        Set<String> allowedApplicationTypes,
        String requiredScope,
        String endpointKey,
        String method,
        String resourceType,
        Long resourceId,
        long requestBytes
    ) {
        return beginAuthenticated(
            authenticated, allowedApplicationTypes, requiredScope, endpointKey, method,
            resourceType, resourceId, requestBytes, 1024 * 1024
        );
    }

    /**
     * 处理{@code beginAuthenticatedUpload}并返回对应结果。
     *
     * @param authenticated {@code authenticated}参数
     * @param allowedApplicationTypes 业务类型
     * @param requiredScope required范围参数
     * @param endpointKey {@code endpointKey}参数
     * @param method {@code method}参数
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param requestBytes {@code requestBytes}参数
     * @param maximumBytes {@code maximumBytes}参数
     * @return 处理结果
     */
    public ApiCallContext beginAuthenticatedUpload(
        AuthenticatedServiceAccount authenticated,
        Set<String> allowedApplicationTypes,
        String requiredScope,
        String endpointKey,
        String method,
        String resourceType,
        Long resourceId,
        long requestBytes,
        long maximumBytes
    ) {
        return beginAuthenticated(
            authenticated, allowedApplicationTypes, requiredScope, endpointKey, method,
            resourceType, resourceId, requestBytes, maximumBytes
        );
    }

    /**
     * 处理{@code beginAuthenticated}并返回对应结果。
     *
     * @param authenticated {@code authenticated}参数
     * @param allowedApplicationTypes 业务类型
     * @param requiredScope required范围参数
     * @param endpointKey {@code endpointKey}参数
     * @param method {@code method}参数
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param requestBytes {@code requestBytes}参数
     * @param maximumBytes {@code maximumBytes}参数
     * @return 处理结果
     */
    private ApiCallContext beginAuthenticated(
        AuthenticatedServiceAccount authenticated,
        Set<String> allowedApplicationTypes,
        String requiredScope,
        String endpointKey,
        String method,
        String resourceType,
        Long resourceId,
        long requestBytes,
        long maximumBytes
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (!allowedApplicationTypes.contains(authenticated.applicationType())
            || !authenticated.scopes().contains(requiredScope)) {
            throw new ServiceException("API凭证scope或应用类型不匹配", 403);
        }
        if (requestBytes < 0 || requestBytes > maximumBytes) {
            throw new ServiceException("API请求体超过允许大小", 400);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Long callId = idGenerator.nextId();
        String requestId = ContentHashing.sha256(UUID.randomUUID().toString());
        if (mapper.insertCall(
            callId, requestId, authenticated.applicationId(), authenticated.credentialId(),
            authenticated.principal().id(), endpointKey, method.toUpperCase(Locale.ROOT),
            requiredScope, resourceType, resourceId, Math.toIntExact(requestBytes), now
        ) != 1) {
            throw new IllegalStateException("API调用审计写入失败");
        }
        Integer count = mapper.consumeRate(
            authenticated.applicationId(), now.withSecond(0).withNano(0), requestsPerMinute, now
        );
        ApiCallContext context = new ApiCallContext(
            callId, requestId, authenticated, System.nanoTime()
        );
        if (count == null) {
            mapper.completeCall(callId, "rate_limited", 429, 0, "RATE_LIMITED", now);
            throw new ServiceException("API调用超过每分钟限制", 429);
        }
        return context;
    }

    /**
     * 处理{@code succeed}相关逻辑。
     *
     * @param context 待处理内容
     * @param statusCode 目标状态
     */
    @Transactional(rollbackFor = Exception.class)
    public void succeed(ApiCallContext context, int statusCode) {
        complete(context, "succeeded", statusCode, null);
    }

    /**
     * 处理{@code fail}相关逻辑。
     *
     * @param context 待处理内容
     * @param throwable {@code throwable}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void fail(ApiCallContext context, Throwable throwable) {
        int statusCode = throwable instanceof ServiceException serviceException
            ? serviceException.getCode() : 500;
        String errorCode = throwable instanceof ServiceException
            ? "HTTP_" + statusCode : throwable.getClass().getSimpleName().toUpperCase(Locale.ROOT);
        complete(context, "failed", statusCode, safeCode(errorCode));
    }

    /**
     * 处理{@code complete}相关逻辑。
     *
     * @param context 待处理内容
     * @param outcome {@code outcome}参数
     * @param statusCode 目标状态
     * @param errorCode {@code errorCode}参数
     */
    private void complete(
        ApiCallContext context,
        String outcome,
        int statusCode,
        String errorCode
    ) {
        long durationMs = Math.max(0, (System.nanoTime() - context.startedNanos()) / 1_000_000);
        mapper.completeCall(
            context.callId(), outcome, statusCode, durationMs, errorCode,
            LocalDateTime.now(ZoneOffset.UTC)
        );
    }

    /**
     * 处理{@code bearerSecret}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String bearerSecret(String value) {
        if (value == null || !value.startsWith("Bearer ")) {
            throw new ServiceException("API凭证无效", 401);
        }
        String secret = value.substring(7).strip();
        if (secret.isBlank() || secret.indexOf(' ') >= 0) {
            throw new ServiceException("API凭证无效", 401);
        }
        return secret;
    }

    /**
     * 处理{@code safeCode}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String safeCode(String value) {
        String normalized = value == null ? "UNKNOWN" : value.replaceAll("[^A-Z0-9_]", "_");
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    /**
     * 封装接口Call相关的不可变数据。
     */
    public record ApiCallContext(
        Long callId,
        String requestId,
        AuthenticatedServiceAccount authenticated,
        long startedNanos
    ) {
    }
}
