package group.aitools.nhs.platform.embed.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.embed.service.EmbedChatPersistenceService;
import group.aitools.nhs.platform.embed.service.EmbedChatRuntimeService;
import group.aitools.nhs.platform.embed.service.EmbedApplicationPolicy;
import group.aitools.nhs.platform.embed.service.EmbedChatRuntimeService.EmbedInvocation;
import group.aitools.nhs.platform.openapi.service.MachineApiGatewayService;
import group.aitools.nhs.platform.openapi.service.MachineApiGatewayService.ApiCallContext;
import group.aitools.nhs.platform.openapi.web.OpenApiResponse;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Set;

/**
 * 提供Open嵌入式会话对话相关的 HTTP 接口，并负责请求校验与结果返回。
 */
@Validated
@RestController
@RequestMapping("/open/v1/embed")
public class OpenEmbedChatController {

    private static final Set<String> APP_TYPES = Set.of("embed", "internal");
    private final MachineApiGatewayService gateway;
    private final EmbedChatPersistenceService persistence;
    private final EmbedChatRuntimeService runtime;
    private final EmbedApplicationPolicy applicationPolicy;

    /**
     * 创建 {@code OpenEmbedChatController} 实例并初始化所需依赖。
     *
     * @param gateway {@code gateway}参数
     * @param persistence {@code persistence}参数
     * @param runtime 运行时参数
     * @param applicationPolicy 应用策略参数
     */
    public OpenEmbedChatController(
        MachineApiGatewayService gateway,
        EmbedChatPersistenceService persistence,
        EmbedChatRuntimeService runtime,
        EmbedApplicationPolicy applicationPolicy
    ) {
        this.gateway = gateway;
        this.persistence = persistence;
        this.runtime = runtime;
        this.applicationPolicy = applicationPolicy;
    }

    /**
     * 创建并保存会话。
     *
     * @param authorization 授权参数
     * @param origin {@code origin}参数
     * @param contentLength 待处理内容
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/sessions")
    public OpenApiResponse<EmbedSessionView> createSession(
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin,
        @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
        @Valid @RequestBody CreateEmbedSessionRequest request
    ) {
        ApiCallContext call = gateway.begin(
            authorization, APP_TYPES, "chat:invoke", "embed_session_create", "POST",
            "agent_version", request.agentVersionId(), contentLength == null ? 0 : contentLength
        );
        try {
            applicationPolicy.requireSessionAllowed(
                call.authenticated(), origin, request.agentVersionId(), request.expiresInMinutes()
            );
            EmbedSessionView result = persistence.createSession(
                call.authenticated(), request.agentVersionId(), request.externalUserKey(),
                request.expiresInMinutes()
            );
            gateway.succeed(call, 200);
            return new OpenApiResponse<>(call.requestId(), result);
        } catch (RuntimeException exception) {
            gateway.fail(call, exception);
            throw exception;
        }
    }

    /**
     * 处理消息并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param authorization 授权参数
     * @param origin {@code origin}参数
     * @param contentLength 待处理内容
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping(
        value = "/sessions/{sessionId}/messages",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public ResponseEntity<Flux<ServerSentEvent<Object>>> message(
        @PathVariable @Positive Long sessionId,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin,
        @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
        @Valid @RequestBody CreateEmbedTurnRequest request
    ) {
        ApiCallContext call = gateway.begin(
            authorization, APP_TYPES, "chat:invoke", "embed_message", "POST",
            "embed_session", sessionId, contentLength == null ? 0 : contentLength
        );
        EmbedInvocation invocation;
        try {
            var session = persistence.ownedActiveSession(call.authenticated(), sessionId);
            applicationPolicy.requireRequestAllowed(
                call.authenticated(), origin, session.getAgentVersionId()
            );
            invocation = runtime.invoke(
                call.authenticated(), sessionId, request.idempotencyKey(), request.input()
            );
        } catch (RuntimeException exception) {
            gateway.fail(call, exception);
            throw exception;
        }
        Flux<ServerSentEvent<Object>> body = Flux.concat(
            Flux.just(ServerSentEvent.builder((Object) Map.of(
                "requestId", call.requestId(),
                "turnId", invocation.turnId(),
                "replayed", invocation.replayed()
            )).event("meta").build()),
            invocation.events().map(event -> ServerSentEvent.builder((Object) event)
                .id(String.valueOf(event.cursor())).event("execution").build())
        ).doOnComplete(() -> gateway.succeed(call, 200))
            .doOnError(error -> gateway.fail(call, error))
            .doOnCancel(() -> gateway.fail(call, new ServiceException("客户端断开Embed流", 499)));
        return ResponseEntity.ok()
            .header("X-Request-Id", call.requestId())
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(body);
    }
}
