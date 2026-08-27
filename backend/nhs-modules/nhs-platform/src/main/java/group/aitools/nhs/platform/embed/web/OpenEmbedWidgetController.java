package group.aitools.nhs.platform.embed.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.conversation.web.ConversationAttachmentView;
import group.aitools.nhs.platform.conversation.service.ConversationGovernanceService;
import group.aitools.nhs.platform.conversation.web.ConversationFeedbackRequest;
import group.aitools.nhs.platform.conversation.web.ConversationFeedbackView;
import group.aitools.nhs.platform.embed.service.EmbedAttachmentService;
import group.aitools.nhs.platform.embed.service.EmbedBrowserCredentialService;
import group.aitools.nhs.platform.embed.service.EmbedBrowserCredentialService.BrowserAccess;
import group.aitools.nhs.platform.embed.service.EmbedBrowserRequestPolicy;
import group.aitools.nhs.platform.embed.service.EmbedChatPersistenceService;
import group.aitools.nhs.platform.embed.service.EmbedChatRuntimeService;
import group.aitools.nhs.platform.embed.service.EmbedChatRuntimeService.EmbedInvocation;
import group.aitools.nhs.platform.embed.service.EmbedWidgetSessionService;
import group.aitools.nhs.platform.browser.service.BrowserSessionApplicationService;
import group.aitools.nhs.platform.openapi.service.MachineApiGatewayService;
import group.aitools.nhs.platform.openapi.service.MachineApiGatewayService.ApiCallContext;
import group.aitools.nhs.platform.openapi.web.OpenApiResponse;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Set;

/**
 * 提供Open嵌入式会话Widget相关的 HTTP 接口，并负责请求校验与结果返回。
 * Public, origin-bound widget API. Application secrets are accepted only by the exchange endpoint. */
@Validated
@RestController
@RequestMapping("/open/v1/embed")
public class OpenEmbedWidgetController {

    private static final Set<String> APP_TYPES = Set.of("embed");
    private static final String HOST_ORIGIN = "X-Embed-Host-Origin";
    private static final String FETCH_SITE = "Sec-Fetch-Site";

    private final MachineApiGatewayService gateway;
    private final EmbedBrowserCredentialService credentials;
    private final EmbedWidgetSessionService sessions;
    private final EmbedChatRuntimeService runtime;
    private final EmbedAttachmentService attachments;
    private final EmbedBrowserRequestPolicy browserRequests;
    private final EmbedChatPersistenceService persistence;
    private final ConversationGovernanceService governance;
    private final BrowserSessionApplicationService browserSessions;

    public OpenEmbedWidgetController(
        MachineApiGatewayService gateway,
        EmbedBrowserCredentialService credentials,
        EmbedWidgetSessionService sessions,
        EmbedChatRuntimeService runtime,
        EmbedAttachmentService attachments,
        EmbedBrowserRequestPolicy browserRequests,
        EmbedChatPersistenceService persistence,
        ConversationGovernanceService governance,
        BrowserSessionApplicationService browserSessions
    ) {
        this.gateway = gateway;
        this.credentials = credentials;
        this.sessions = sessions;
        this.runtime = runtime;
        this.attachments = attachments;
        this.browserRequests = browserRequests;
        this.persistence = persistence;
        this.governance = governance;
        this.browserSessions = browserSessions;
    }

    /**
     * 判断sue浏览器凭据是否满足要求。
     *
     * @param authorization 授权参数
     * @param contentLength 待处理内容
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/browser-credentials")
    public OpenApiResponse<EmbedBrowserCredentialView> issueBrowserCredential(
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
        @Valid @RequestBody IssueEmbedBrowserCredentialRequest request
    ) {
        ApiCallContext call = gateway.begin(
            authorization, APP_TYPES, "chat:invoke", "embed_browser_credential", "POST",
            "agent_version", request.agentVersionId(), contentLength == null ? 0 : contentLength
        );
        try {
            EmbedBrowserCredentialView result = credentials.issueLaunch(
                call.authenticated(), request.origin(), request.agentVersionId(),
                request.externalUserKey(), request.sessionMinutes()
            );
            gateway.succeed(call, 200);
            return new OpenApiResponse<>(call.requestId(), result);
        } catch (RuntimeException exception) {
            gateway.fail(call, exception);
            throw exception;
        }
    }

    /**
     * 创建并保存会话。
     *
     * @param authorization 授权参数
     * @param hostOrigin {@code hostOrigin}参数
     * @param fetchSite {@code fetchSite}参数
     * @param browserOrigin 浏览器Origin参数
     * @return 处理结果
     */
    @PostMapping("/widget/sessions")
    public OpenApiResponse<EmbedWidgetBootstrapView> createSession(
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(HOST_ORIGIN) String hostOrigin,
        @RequestHeader(value = FETCH_SITE, required = false) String fetchSite,
        @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String browserOrigin
    ) {
        browserRequests.requireSameOrigin(fetchSite, browserOrigin);
        BrowserAccess access = credentials.authenticateLaunch(authorization, hostOrigin);
        ApiCallContext call = begin(access, "embed_widget_session", "POST", "agent_version",
            access.credential().getAgentVersionId(), 0);
        try {
            EmbedWidgetBootstrapView result = sessions.create(access);
            gateway.succeed(call, 200);
            return new OpenApiResponse<>(call.requestId(), result);
        } catch (RuntimeException exception) {
            gateway.fail(call, exception);
            throw exception;
        }
    }

    /**
     * 处理{@code state}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param authorization 授权参数
     * @param hostOrigin {@code hostOrigin}参数
     * @param fetchSite {@code fetchSite}参数
     * @param browserOrigin 浏览器Origin参数
     * @return 处理结果
     */
    @GetMapping("/widget/sessions/{sessionId}")
    public OpenApiResponse<EmbedWidgetStateView> state(
        @PathVariable @Positive Long sessionId,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(HOST_ORIGIN) String hostOrigin,
        @RequestHeader(value = FETCH_SITE, required = false) String fetchSite,
        @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String browserOrigin
    ) {
        browserRequests.requireSameOrigin(fetchSite, browserOrigin);
        BrowserAccess access = credentials.authenticateSession(authorization, hostOrigin, sessionId);
        ApiCallContext call = begin(access, "embed_widget_state", "GET", "embed_session", sessionId, 0);
        try {
            EmbedWidgetStateView result = sessions.state(access, sessionId);
            gateway.succeed(call, 200);
            return new OpenApiResponse<>(call.requestId(), result);
        } catch (RuntimeException exception) {
            gateway.fail(call, exception);
            throw exception;
        }
    }

    /**
     * 清理或重置{@code reset}。
     *
     * @param sessionId 资源标识
     * @param authorization 授权参数
     * @param hostOrigin {@code hostOrigin}参数
     * @param fetchSite {@code fetchSite}参数
     * @param browserOrigin 浏览器Origin参数
     * @return 处理结果
     */
    @PostMapping("/widget/sessions/{sessionId}/reset")
    public OpenApiResponse<EmbedWidgetBootstrapView> reset(
        @PathVariable @Positive Long sessionId,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(HOST_ORIGIN) String hostOrigin,
        @RequestHeader(value = FETCH_SITE, required = false) String fetchSite,
        @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String browserOrigin
    ) {
        browserRequests.requireSameOrigin(fetchSite, browserOrigin);
        BrowserAccess access = authenticateResetCredential(authorization, hostOrigin, sessionId);
        ApiCallContext call = begin(access, "embed_widget_reset", "POST", "embed_session", sessionId, 0);
        try {
            EmbedWidgetBootstrapView result = sessions.reset(access, sessionId);
            gateway.succeed(call, 200);
            return new OpenApiResponse<>(call.requestId(), result);
        } catch (RuntimeException exception) {
            gateway.fail(call, exception);
            throw exception;
        }
    }

    /**
     * 处理close浏览器并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param authorization 授权参数
     * @param hostOrigin {@code hostOrigin}参数
     * @param fetchSite {@code fetchSite}参数
     * @param browserOrigin 浏览器Origin参数
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/widget/sessions/{sessionId}/browser/close")
    public OpenApiResponse<Map<String, Object>> closeBrowser(
        @PathVariable @Positive Long sessionId,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(HOST_ORIGIN) String hostOrigin,
        @RequestHeader(value = FETCH_SITE, required = false) String fetchSite,
        @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String browserOrigin,
        @Valid @RequestBody EmbedBrowserSessionRequest request
    ) {
        return browserControl(sessionId, authorization, hostOrigin, fetchSite, browserOrigin,
            request, false);
    }

    /**
     * 清理或重置浏览器。
     *
     * @param sessionId 资源标识
     * @param authorization 授权参数
     * @param hostOrigin {@code hostOrigin}参数
     * @param fetchSite {@code fetchSite}参数
     * @param browserOrigin 浏览器Origin参数
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/widget/sessions/{sessionId}/browser/reset")
    public OpenApiResponse<Map<String, Object>> resetBrowser(
        @PathVariable @Positive Long sessionId,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(HOST_ORIGIN) String hostOrigin,
        @RequestHeader(value = FETCH_SITE, required = false) String fetchSite,
        @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String browserOrigin,
        @Valid @RequestBody EmbedBrowserSessionRequest request
    ) {
        return browserControl(sessionId, authorization, hostOrigin, fetchSite, browserOrigin,
            request, true);
    }

    /**
     * 处理浏览器Control并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param authorization 授权参数
     * @param hostOrigin {@code hostOrigin}参数
     * @param fetchSite {@code fetchSite}参数
     * @param browserOrigin 浏览器Origin参数
     * @param request 请求参数
     * @param clearProfile clear配置档案参数
     * @return 处理结果
     */
    private OpenApiResponse<Map<String, Object>> browserControl(
        Long sessionId,
        String authorization,
        String hostOrigin,
        String fetchSite,
        String browserOrigin,
        EmbedBrowserSessionRequest request,
        boolean clearProfile
    ) {
        browserRequests.requireSameOrigin(fetchSite, browserOrigin);
        BrowserAccess access = credentials.authenticateSession(authorization, hostOrigin, sessionId);
        ApiCallContext call = begin(access, clearProfile ? "embed_browser_reset" : "embed_browser_close",
            "POST", "browser_session", request.browserSessionId(), 0);
        try {
            Map<String, Object> result = browserSessions.runAsRuntimePrincipal(
                access.authenticated().principal(),
                () -> browserSessions.close(request.browserSessionId(), clearProfile)
            );
            gateway.succeed(call, 200);
            return new OpenApiResponse<>(call.requestId(), result);
        } catch (RuntimeException exception) {
            gateway.fail(call, exception);
            throw exception;
        }
    }

    /**
     * 处理{@code upload}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param authorization 授权参数
     * @param hostOrigin {@code hostOrigin}参数
     * @param fetchSite {@code fetchSite}参数
     * @param browserOrigin 浏览器Origin参数
     * @param file 文件参数
     * @return 处理结果
     */
    @PostMapping(
        value = "/widget/sessions/{sessionId}/attachments",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public OpenApiResponse<ConversationAttachmentView> upload(
        @PathVariable @Positive Long sessionId,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(HOST_ORIGIN) String hostOrigin,
        @RequestHeader(value = FETCH_SITE, required = false) String fetchSite,
        @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String browserOrigin,
        @RequestPart("file") MultipartFile file
    ) {
        browserRequests.requireSameOrigin(fetchSite, browserOrigin);
        BrowserAccess access = credentials.authenticateSession(authorization, hostOrigin, sessionId);
        ApiCallContext call = gateway.beginAuthenticatedUpload(
            access.authenticated(), APP_TYPES, "chat:invoke", "embed_widget_attachment", "POST",
            "embed_session", sessionId, file == null ? 0 : file.getSize(),
            EmbedAttachmentService.MAX_UPLOAD_BYTES
        );
        try {
            ConversationAttachmentView result = attachments.upload(access.authenticated(), sessionId, file);
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
     * @param hostOrigin {@code hostOrigin}参数
     * @param fetchSite {@code fetchSite}参数
     * @param browserOrigin 浏览器Origin参数
     * @param contentLength 待处理内容
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping(
        value = "/widget/sessions/{sessionId}/messages",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public ResponseEntity<Flux<ServerSentEvent<Object>>> message(
        @PathVariable @Positive Long sessionId,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(HOST_ORIGIN) String hostOrigin,
        @RequestHeader(value = FETCH_SITE, required = false) String fetchSite,
        @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String browserOrigin,
        @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
        @Valid @RequestBody CreateEmbedWidgetTurnRequest request
    ) {
        browserRequests.requireSameOrigin(fetchSite, browserOrigin);
        BrowserAccess access = credentials.authenticateSession(authorization, hostOrigin, sessionId);
        ApiCallContext call = begin(access, "embed_widget_message", "POST", "embed_session", sessionId,
            contentLength == null ? 0 : contentLength);
        try {
            EmbedInvocation invocation = runtime.invokeWidget(
                access.authenticated(), sessionId, request.idempotencyKey(), request.input(),
                request.attachmentIds(), request.context(), 0
            );
            return stream(call, invocation);
        } catch (RuntimeException exception) {
            gateway.fail(call, exception);
            throw exception;
        }
    }

    /**
     * 处理{@code resume}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param turnId 资源标识
     * @param cursor {@code cursor}参数
     * @param authorization 授权参数
     * @param hostOrigin {@code hostOrigin}参数
     * @param fetchSite {@code fetchSite}参数
     * @param browserOrigin 浏览器Origin参数
     * @return 处理结果
     */
    @GetMapping(
        value = "/widget/sessions/{sessionId}/turns/{turnId}/events",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public ResponseEntity<Flux<ServerSentEvent<Object>>> resume(
        @PathVariable @Positive Long sessionId,
        @PathVariable @Positive Long turnId,
        @RequestParam(defaultValue = "0") @Min(0) long cursor,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(HOST_ORIGIN) String hostOrigin,
        @RequestHeader(value = FETCH_SITE, required = false) String fetchSite,
        @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String browserOrigin
    ) {
        browserRequests.requireSameOrigin(fetchSite, browserOrigin);
        BrowserAccess access = credentials.authenticateSession(authorization, hostOrigin, sessionId);
        ApiCallContext call = begin(access, "embed_widget_resume", "GET", "embed_turn", turnId, 0);
        try {
            return stream(call, runtime.resumeWidget(access.authenticated(), sessionId, turnId, cursor));
        } catch (RuntimeException exception) {
            gateway.fail(call, exception);
            throw exception;
        }
    }

    /**
     * 处理{@code stop}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param turnId 资源标识
     * @param authorization 授权参数
     * @param hostOrigin {@code hostOrigin}参数
     * @param fetchSite {@code fetchSite}参数
     * @param browserOrigin 浏览器Origin参数
     * @return 处理结果
     */
    @PostMapping("/widget/sessions/{sessionId}/turns/{turnId}/stop")
    public OpenApiResponse<EmbedTurnView> stop(
        @PathVariable @Positive Long sessionId,
        @PathVariable @Positive Long turnId,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(HOST_ORIGIN) String hostOrigin,
        @RequestHeader(value = FETCH_SITE, required = false) String fetchSite,
        @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String browserOrigin
    ) {
        browserRequests.requireSameOrigin(fetchSite, browserOrigin);
        BrowserAccess access = credentials.authenticateSession(authorization, hostOrigin, sessionId);
        ApiCallContext call = begin(access, "embed_widget_stop", "POST", "embed_turn", turnId, 0);
        try {
            EmbedTurnView result = EmbedTurnView.from(
                runtime.stopWidget(access.authenticated(), sessionId, turnId)
            );
            gateway.succeed(call, 200);
            return new OpenApiResponse<>(call.requestId(), result);
        } catch (RuntimeException exception) {
            gateway.fail(call, exception);
            throw exception;
        }
    }

    /**
     * 处理反馈并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param authorization 授权参数
     * @param hostOrigin {@code hostOrigin}参数
     * @param fetchSite {@code fetchSite}参数
     * @param browserOrigin 浏览器Origin参数
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/widget/sessions/{sessionId}/feedback")
    public OpenApiResponse<ConversationFeedbackView> feedback(
        @PathVariable @Positive Long sessionId,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(HOST_ORIGIN) String hostOrigin,
        @RequestHeader(value = FETCH_SITE, required = false) String fetchSite,
        @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String browserOrigin,
        @Valid @RequestBody ConversationFeedbackRequest request
    ) {
        browserRequests.requireSameOrigin(fetchSite, browserOrigin);
        BrowserAccess access = credentials.authenticateSession(authorization, hostOrigin, sessionId);
        ApiCallContext call = begin(access, "embed_widget_feedback", "POST", "embed_session", sessionId, 0);
        try {
            var session = persistence.ownedActiveSession(access.authenticated(), sessionId);
            ConversationFeedbackView result = governance.saveFeedback(
                access.authenticated().principal(), session.getConversationId(), request
            );
            gateway.succeed(call, 200);
            return new OpenApiResponse<>(call.requestId(), result);
        } catch (RuntimeException exception) {
            gateway.fail(call, exception);
            throw exception;
        }
    }

    /**
     * 处理{@code begin}并返回对应结果。
     *
     * @param access {@code access}参数
     * @param endpoint {@code endpoint}参数
     * @param method {@code method}参数
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param bytes {@code bytes}参数
     * @return 处理结果
     */
    private ApiCallContext begin(
        BrowserAccess access,
        String endpoint,
        String method,
        String resourceType,
        Long resourceId,
        long bytes
    ) {
        return gateway.beginAuthenticated(
            access.authenticated(), APP_TYPES, "chat:invoke", endpoint, method,
            resourceType, resourceId, bytes
        );
    }

    /**
     * 处理authenticateReset凭据并返回对应结果。
     *
     * @param authorization 授权参数
     * @param hostOrigin {@code hostOrigin}参数
     * @param sessionId 资源标识
     * @return 处理结果
     */
    private BrowserAccess authenticateResetCredential(
        String authorization,
        String hostOrigin,
        Long sessionId
    ) {
        try {
            return credentials.authenticateSession(authorization, hostOrigin, sessionId);
        } catch (ServiceException exception) {
            if (exception.getCode() != 401) {
                throw exception;
            }
            // A fresh launch capability is allowed to rotate the matching expired session.
            return credentials.authenticateLaunch(authorization, hostOrigin);
        }
    }

    /**
     * 处理{@code stream}并返回对应结果。
     *
     * @param call {@code call}参数
     * @param invocation 调用参数
     * @return 处理结果
     */
    private ResponseEntity<Flux<ServerSentEvent<Object>>> stream(
        ApiCallContext call,
        EmbedInvocation invocation
    ) {
        Flux<ServerSentEvent<Object>> body = Flux.concat(
            Flux.just(ServerSentEvent.builder((Object) Map.of(
                "requestId", call.requestId(),
                "turnId", invocation.turnId(),
                "replayed", invocation.replayed()
            )).event("meta").build()),
            invocation.events().map(event -> ServerSentEvent.builder((Object) event)
                .id(String.valueOf(event.cursor())).event("execution").build())
        ).doOnComplete(() -> gateway.succeed(call, 200))
            // A browser disconnect only cancels delivery; it is not a successful API call.
            // Keep the persisted audit outcome aligned with the resumable turn state.
            .doOnCancel(() -> gateway.fail(call, new ServiceException("客户端断开Embed流", 499)))
            .doOnError(error -> gateway.fail(call, error));
        return ResponseEntity.ok()
            .header("X-Request-Id", call.requestId())
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(body);
    }
}
