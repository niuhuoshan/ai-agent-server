package group.aitools.nhs.platform.browser.service;

import group.aitools.nhs.platform.browser.domain.BrowserSession;
import group.aitools.nhs.platform.browser.repository.BrowserSessionRepository;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.connector.service.ConnectorEndpointPolicy;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.nhs.service.GeneratedFileService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 负责浏览器会话相关的业务编排与领域规则处理。
 * Owner-scoped browser control facade; all untrusted browser code stays in the Worker container. */
@Service
public class BrowserSessionApplicationService {

    private static final String WORKER_ID = "nhs-browser";
    private static final int MAX_EVENT_JSON = 1_000_000;
    private static final int MAX_PROFILE_KEY = 128;
    private static final int MAX_URL = 2048;
    private static final int MAX_SELECTOR = 1000;
    private static final int MAX_FILL_VALUE = 20_000;
    private static final int MAX_KEY = 64;
    private static final int MAX_MANUAL_TEXT = 2000;
    private static final int MAX_TAB_ID = 255;
    private static final int MAX_HANDOFF_REASON = 255;

    private final CurrentPrincipalProvider principalProvider;
    private final BrowserSessionRepository repository;
    private final BrowserWorkerClient workerClient;
    private final PlatformIdGenerator idGenerator;
    private final JsonMapper jsonMapper;
    private final ConnectorEndpointPolicy endpointPolicy;
    private GeneratedFileService generatedFileService;
    private final ThreadLocal<CurrentPrincipal> runtimePrincipal = new ThreadLocal<>();

    public BrowserSessionApplicationService(
        CurrentPrincipalProvider principalProvider,
        BrowserSessionRepository repository,
        BrowserWorkerClient workerClient,
        PlatformIdGenerator idGenerator,
        JsonMapper jsonMapper,
        ConnectorEndpointPolicy endpointPolicy
    ) {
        this.principalProvider = principalProvider;
        this.repository = repository;
        this.workerClient = workerClient;
        this.idGenerator = idGenerator;
        this.jsonMapper = jsonMapper;
        this.endpointPolicy = endpointPolicy;
    }

    /**
     * 设置Generated文件Service。
     *
     * @param generatedFileService generated文件Service参数
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setGeneratedFileService(GeneratedFileService generatedFileService) {
        this.generatedFileService = generatedFileService;
    }

    /**
 * 执行As运行时操作主体相关的处理流程。
 * Runs a browser tool against the frozen runtime principal instead of the request user. */
    public <T> T runAsRuntimePrincipal(CurrentPrincipal principal, Supplier<T> operation) {
        if (principal == null || operation == null) {
            throw new IllegalArgumentException("运行时主体和操作不能为空");
        }
        CurrentPrincipal previous = runtimePrincipal.get();
        runtimePrincipal.set(principal);
        try {
            return operation.get();
        } finally {
            if (previous == null) runtimePrincipal.remove();
            else runtimePrincipal.set(previous);
        }
    }

    /**
     * 处理{@code open}并返回对应结果。
     *
     * @param profileKey 配置档案Key参数
     * @param startUrl {@code startUrl}参数
     * @return 处理结果
     */
    public Map<String, Object> open(String profileKey, String startUrl) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = principal();
        String normalizedProfile = text(profileKey, MAX_PROFILE_KEY, "profileKey", false);
        String normalizedUrl = url(startUrl, false);
        Long id = idGenerator.nextId();
        String sessionKey = "bs_" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        repository.insertSession(new BrowserSession(
            id, principal.id(), sessionKey, null, normalizedProfile, "opening", normalizedUrl,
            null, null, null, "none", null, null, null, null, null, now, now, null
        ));
        Map<String, Object> request = new LinkedHashMap<>();
        if (normalizedProfile != null) request.put("profile_key", normalizedProfile);
        if (normalizedUrl != null) request.put("start_url", normalizedUrl);
        try {
            Map<String, Object> worker = workerClient.open(
                sessionKey, normalizedProfile, normalizedUrl, principal.id().toString()
            );
            String workerSessionId = firstText(worker, "session_id", "worker_session_id", "id");
            if (workerSessionId == null) workerSessionId = sessionKey;
            String currentUrl = firstText(worker, "current_url", "url");
            String pageTitle = firstText(worker, "page_title", "title");
            repository.markOpened(id, principal.id(), workerSessionId, currentUrl, pageTitle, now);
            renewLease(id, principal.id(), now);
            event(id, principal.id(), "open", "success", request, worker, null, now);
            return view(repository.findOwned(id, principal.id()), worker);
        } catch (RuntimeException exception) {
            repository.markFailed(id, principal.id(), LocalDateTime.now());
            event(id, principal.id(), "open", "failed", request, null, message(exception), LocalDateTime.now());
            throw exception;
        }
    }

    /**
     * 查询{@code list}列表。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> list(int limit) {
        CurrentPrincipal principal = principal();
        int bounded = Math.max(1, Math.min(limit, 100));
        return repository.listOwned(principal.id(), bounded).stream()
            .map(this::view)
            .toList();
    }

    /**
 * 处理工作进程健康状态并返回对应结果。
 *
     * Reads Worker lifecycle facts and reconciles persisted sessions with the
     * currently live Worker process. A restarted Worker has no old sessions;
     * those rows must become explicit failures instead of remaining actionable.
     */
    public Map<String, Object> workerHealth() {
        principal();
        LocalDateTime checkedAt = LocalDateTime.now();
        Map<String, Object> health;
        try {
            health = workerClient.health();
        } catch (RuntimeException exception) {
            int invalidated = reconcileWorkerSessions(Set.of(), checkedAt, true);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("available", false);
            result.put("status", "unavailable");
            result.put("error", bounded(message(exception), 500));
            result.put("invalidatedSessions", invalidated);
            result.put("checkedAt", checkedAt);
            return result;
        }
        Object rawSessionIds = health.get("session_ids");
        int invalidated = rawSessionIds instanceof List<?>
            ? reconcileWorkerSessions(workerSessionIds(rawSessionIds), checkedAt, false) : 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("status", firstText(health, "status"));
        result.put("workerGeneration", firstText(health, "worker_generation", "workerGeneration"));
        result.put("startedAt", firstText(health, "started_at", "startedAt"));
        result.put("sessions", number(health.get("sessions")));
        result.put("maxSessions", number(health.get("max_sessions")));
        result.put("maxTabsPerSession", number(health.get("max_tabs_per_session")));
        result.put("invalidatedSessions", invalidated);
        result.put("checkedAt", checkedAt);
        return result;
    }

    /**
     * 处理reconcile工作进程Sessions并返回对应结果。
     *
     * @param liveSessions {@code liveSessions}参数
     * @param now {@code now}参数
     * @param workerUnavailable 工作进程Unavailable参数
     * @return 处理结果
     */
    private int reconcileWorkerSessions(Set<String> liveSessions, LocalDateTime now, boolean workerUnavailable) {
        int invalidated = 0;
        for (BrowserSession session : repository.listOpenWorkerSessions()) {
            if (!workerUnavailable && liveSessions.contains(worker(session))) {
                continue;
            }
            if (repository.markWorkerUnavailable(session.id(), session.ownerId(), now) == 1) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("workerUnavailable", workerUnavailable);
                response.put("reason", workerUnavailable ? "Worker 健康检查失败" : "Worker 重启后会话不存在");
                event(session.id(), session.ownerId(), "worker_reconcile", "failed", Map.of(), response,
                    response.get("reason").toString(), now);
                invalidated++;
            }
        }
        return invalidated;
    }

    /**
     * 处理工作进程会话Ids并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private Set<String> workerSessionIds(Object value) {
        if (!(value instanceof List<?> list)) return Set.of();
        Set<String> result = new HashSet<>();
        for (Object item : list) {
            if (item instanceof String text && !text.isBlank()) result.add(text);
        }
        return result;
    }

    /**
     * 处理{@code number}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * 获取{@code get}。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    public Map<String, Object> get(Long id) {
        CurrentPrincipal principal = principal();
        return view(require(id, principal), null);
    }

    /**
 * 处理{@code requestHandoff}并返回对应结果。
 * Requests a durable pause for AI browser actions until a human returns control. */
    public Map<String, Object> requestHandoff(Long id, String rawReason) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principal();
        BrowserSession session = require(id, principal);
        ensureOpenForHandoff(session);
        String reason = text(rawReason, MAX_HANDOFF_REASON, "reason", false);
        if (reason == null) reason = "需要人工完成浏览器操作";
        if (handoffBlocking(session)) {
            Map<String, Object> result = view(session, null);
            result.put("replayed", true);
            return result;
        }
        LocalDateTime now = LocalDateTime.now();
        if (repository.requestHandoff(id, principal.id(), reason, now) != 1) {
            BrowserSession current = require(id, principal);
            if (handoffBlocking(current)) {
                Map<String, Object> result = view(current, null);
                result.put("replayed", true);
                return result;
            }
            throw new ServiceException("浏览器会话无法请求人工接管", HttpStatus.CONFLICT);
        }
        BrowserSession updated = require(id, principal);
        event(id, principal.id(), "handoff_request", "success",
            Map.of("reason_length", reason.length(), "reason_redacted", true),
            Map.of("handoff_status", updated.handoffStatus()), null, now);
        return view(updated, null);
    }

    /**
 * 处理{@code takeHandoff}并返回对应结果。
 * Claims a requested handoff for the logged-in human owner. */
    public Map<String, Object> takeHandoff(Long id) {
        CurrentPrincipal principal = principal();
        requireHuman(principal);
        BrowserSession session = require(id, principal);
        ensureOpenForHandoff(session);
        if ("human_control".equals(session.handoffStatus()) && principal.id().equals(session.handoffUserId())) {
            Map<String, Object> result = view(session, null);
            result.put("replayed", true);
            return result;
        }
        if (!"requested".equals(session.handoffStatus())) {
            throw new ServiceException("浏览器会话当前没有待接管请求", HttpStatus.CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now();
        if (repository.takeHandoff(id, principal.id(), principal.id(), now) != 1) {
            throw new ServiceException("浏览器会话接管状态已被更新，请刷新后重试", HttpStatus.CONFLICT);
        }
        BrowserSession updated = require(id, principal);
        event(id, principal.id(), "handoff_takeover", "success", Map.of(),
            Map.of("handoff_status", updated.handoffStatus(), "handoff_user_id", principal.id()), null, now);
        return view(updated, null);
    }

    /**
 * 处理{@code returnHandoff}并返回对应结果。
 * Explicitly returns browser control to the AI runtime. */
    public Map<String, Object> returnHandoff(Long id) {
        CurrentPrincipal principal = principal();
        requireHuman(principal);
        BrowserSession session = require(id, principal);
        ensureOpenForHandoff(session);
        if ("returned".equals(session.handoffStatus()) || "none".equals(session.handoffStatus())) {
            Map<String, Object> result = view(session, null);
            result.put("replayed", true);
            return result;
        }
        if (!"human_control".equals(session.handoffStatus())
            && !"requested".equals(session.handoffStatus())) {
            throw new ServiceException("浏览器会话当前无法交还 AI", HttpStatus.CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now();
        if (repository.returnHandoff(id, principal.id(), now) != 1) {
            throw new ServiceException("浏览器会话交还状态已被更新，请刷新后重试", HttpStatus.CONFLICT);
        }
        BrowserSession updated = require(id, principal);
        event(id, principal.id(), "handoff_return", "success", Map.of(),
            Map.of("handoff_status", updated.handoffStatus()), null, now);
        return view(updated, null);
    }

    /**
     * 处理{@code navigate}并返回对应结果。
     *
     * @param id 资源标识
     * @param rawUrl {@code rawUrl}参数
     * @return 处理结果
     */
    public Map<String, Object> navigate(Long id, String rawUrl) {
        return operation(id, "navigate", Map.of("url", url(rawUrl, true)),
            (session, owner) -> workerClient.navigate(worker(session), owner.get("url").toString()));
    }

    /**
     * 处理快照并返回对应结果。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    public Map<String, Object> snapshot(Long id) {
        return operation(id, "snapshot", Map.of(),
            (session, ignored) -> workerClient.snapshot(worker(session)));
    }

    /**
     * 处理{@code click}并返回对应结果。
     *
     * @param id 资源标识
     * @param rawSelector {@code rawSelector}参数
     * @return 处理结果
     */
    public Map<String, Object> click(Long id, String rawSelector) {
        String selector = text(rawSelector, MAX_SELECTOR, "selector", true);
        return operation(id, "click", Map.of("selector", selector),
            (session, owner) -> workerClient.click(worker(session), owner.get("selector").toString()));
    }

    /**
     * 处理{@code fill}并返回对应结果。
     *
     * @param id 资源标识
     * @param rawSelector {@code rawSelector}参数
     * @param rawValue {@code rawValue}参数
     * @return 处理结果
     */
    public Map<String, Object> fill(Long id, String rawSelector, String rawValue) {
        String selector = text(rawSelector, MAX_SELECTOR, "selector", true);
        String value = text(rawValue, MAX_FILL_VALUE, "value", false);
        if (value == null) value = "";
        Map<String, Object> request = Map.of("selector", selector, "value", value);
        return operation(id, "fill", request,
            (session, owner) -> workerClient.fill(
                worker(session), owner.get("selector").toString(), owner.get("value").toString()
            ));
    }

    /**
     * 处理{@code close}并返回对应结果。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    public Map<String, Object> close(Long id) {
        return close(id, false);
    }

    /**
 * 处理{@code close}并返回对应结果。
 * Closes a session, optionally clearing its Worker profile and login state. */
    public Map<String, Object> close(Long id, boolean destroyProfile) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (destroyProfile) return clearSessionProfile(id);
        CurrentPrincipal principal = principal();
        BrowserSession session = require(id, principal);
        if (runtimePrincipal.get() != null && handoffBlocking(session)) {
            throw new ServiceException("浏览器会话已暂停等待人工接管，请等待用户交还 AI", 423);
        }
        Map<String, Object> response = Map.of();
        try {
            if (session.workerSessionId() != null && !session.workerSessionId().isBlank()
                && !"closed".equals(session.status())) {
                response = workerClient.close(session.workerSessionId());
            }
            LocalDateTime now = LocalDateTime.now();
            repository.markClosed(id, principal.id(), now);
            repository.deleteLease(id, principal.id());
            event(id, principal.id(), "close", "success", Map.of(), response, null, now);
            Map<String, Object> result = view(repository.findOwned(id, principal.id()), response);
            result.put("closed", true);
            return result;
        } catch (RuntimeException exception) {
            if (isWorkerUnavailable(exception)) {
                repository.markWorkerUnavailable(id, principal.id(), LocalDateTime.now());
            }
            event(id, principal.id(), "close", "failed", Map.of(), null, message(exception), LocalDateTime.now());
            throw exception;
        }
    }

    /**
 * 清理或重置Owned浏览器Profiles。
 * Clears all browser profiles owned by the current principal. */
    public Map<String, Object> clearOwnedBrowserProfiles() {
        CurrentPrincipal principal = principal();
        Map<String, Object> worker = workerClient.clearProfile(null, principal.id().toString());
        LocalDateTime now = LocalDateTime.now();
        int cleared = 0;
        for (BrowserSession session : repository.listOwned(principal.id(), 100)) {
            if (!Set.of("open", "opening", "closing").contains(session.status())) continue;
            if (repository.markClosed(session.id(), principal.id(), now) == 1) {
                repository.deleteLease(session.id(), principal.id());
                event(session.id(), principal.id(), "profile_clear", "success", Map.of(), worker, null, now);
                cleared++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cleared", true);
        result.put("clearedSessions", cleared);
        result.put("worker", worker);
        return result;
    }

    /**
     * 清理或重置会话配置档案。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    private Map<String, Object> clearSessionProfile(Long id) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        CurrentPrincipal principal = principal();
        BrowserSession target = require(id, principal);
        if (runtimePrincipal.get() != null && handoffBlocking(target)) {
            throw new ServiceException("浏览器会话已暂停等待人工接管，请等待用户交还 AI", 423);
        }
        Map<String, Object> worker = workerClient.clearProfile(
            target.profileKey(), principal.id().toString()
        );
        LocalDateTime now = LocalDateTime.now();
        int cleared = 0;
        for (BrowserSession session : repository.listOwned(principal.id(), 100)) {
            boolean sameProfile = target.profileKey() == null
                ? session.profileKey() == null : target.profileKey().equals(session.profileKey());
            if (!sameProfile || !Set.of("open", "opening", "closing").contains(session.status())) continue;
            if (repository.markClosed(session.id(), principal.id(), now) == 1) {
                repository.deleteLease(session.id(), principal.id());
                event(session.id(), principal.id(), "profile_clear", "success", Map.of(), worker, null, now);
                cleared++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>(view(repository.findOwned(id, principal.id()), worker));
        result.put("closed", true);
        result.put("profileCleared", true);
        result.put("clearedSessions", cleared);
        return result;
    }

    /**
     * 处理操作并返回对应结果。
     *
     * @param id 资源标识
     * @param eventType 业务类型
     * @param request 请求参数
     * @param operation 操作参数
     * @return 处理结果
     */
    private Map<String, Object> operation(
        Long id,
        String eventType,
        Map<String, Object> request,
        BrowserOperation operation
    ) {
        CurrentPrincipal principal = principal();
        BrowserSession session = requireOpen(id, principal);
        LocalDateTime started = LocalDateTime.now();
        try {
            renewLease(session.id(), principal.id(), started);
            Map<String, Object> response = operation.call(session, request);
            repository.updatePage(
                session.id(), principal.id(), firstText(response, "current_url", "url"),
                firstText(response, "page_title", "title"),
                firstText(response, "active_tab_id", "activeTabId"), tabJson(response), LocalDateTime.now()
            );
            event(session.id(), principal.id(), eventType, "success", auditRequest(eventType, request), response, null, started);
            return result(session.id(), principal.id(), response);
        } catch (RuntimeException exception) {
            if (isWorkerUnavailable(exception)) {
                repository.markWorkerUnavailable(session.id(), principal.id(), LocalDateTime.now());
            }
            event(session.id(), principal.id(), eventType, "failed", auditRequest(eventType, request), null, message(exception), started);
            throw exception;
        }
    }

    /**
     * 处理结果并返回对应结果。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @param worker 工作进程参数
     * @return 处理结果
     */
    private Map<String, Object> result(Long id, Long ownerId, Map<String, Object> worker) {
        Map<String, Object> result = view(repository.findOwned(id, ownerId), worker);
        result.put("worker", worker == null ? Map.of() : worker);
        return result;
    }

    /**
     * 校验{@code require}，并在条件不满足时终止处理。
     *
     * @param id 资源标识
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private BrowserSession require(Long id, CurrentPrincipal principal) {
        if (id == null || id <= 0) throw new ServiceException("浏览器会话 ID 无效", HttpStatus.BAD_REQUEST);
        BrowserSession value = repository.findOwned(id, principal.id());
        if (value == null) throw new ServiceException("浏览器会话不存在或无权访问", HttpStatus.NOT_FOUND);
        return value;
    }

    /**
     * 校验{@code Open}，并在条件不满足时终止处理。
     *
     * @param id 资源标识
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private BrowserSession requireOpen(Long id, CurrentPrincipal principal) {
        BrowserSession value = require(id, principal);
        if (!"open".equals(value.status()) || value.workerSessionId() == null) {
            throw new ServiceException("浏览器会话当前不可操作", HttpStatus.CONFLICT);
        }
        if (runtimePrincipal.get() != null && handoffBlocking(value)) {
            throw new ServiceException("浏览器会话已暂停等待人工接管，请等待用户交还 AI", 423);
        }
        return value;
    }

    /**
     * 校验{@code OpenForHandoff}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     */
    private void ensureOpenForHandoff(BrowserSession value) {
        if (!"open".equals(value.status()) || value.workerSessionId() == null) {
            throw new ServiceException("浏览器会话当前不可接管", HttpStatus.CONFLICT);
        }
    }

    /**
     * 处理{@code handoffBlocking}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean handoffBlocking(BrowserSession value) {
        return "requested".equals(value.handoffStatus()) || "human_control".equals(value.handoffStatus());
    }

    /**
     * 校验{@code Human}，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     */
    private void requireHuman(CurrentPrincipal principal) {
        if (!principal.isHuman()) {
            throw new ServiceException("只有用户可以接管或交还浏览器会话", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 处理操作主体并返回对应结果。
     *
     * @return 处理结果
     */
    private CurrentPrincipal principal() {
        CurrentPrincipal runtime = runtimePrincipal.get();
        CurrentPrincipal value = runtime == null ? principalProvider.currentPrincipal() : runtime;
        if (value == null) throw new ServiceException("未找到当前用户", HttpStatus.FORBIDDEN);
        return value;
    }

    /**
     * 处理{@code renewLease}相关逻辑。
     *
     * @param sessionId 资源标识
     * @param ownerId 资源标识
     * @param now {@code now}参数
     */
    private void renewLease(Long sessionId, Long ownerId, LocalDateTime now) {
        repository.upsertLease(
            idGenerator.nextId(), sessionId, ownerId, WORKER_ID,
            UUID.randomUUID().toString().replace("-", ""), now.plusSeconds(60), now
        );
    }

    /**
     * 处理{@code press}并返回对应结果。
     *
     * @param id 资源标识
     * @param rawKey {@code rawKey}参数
     * @return 处理结果
     */
    public Map<String, Object> press(Long id, String rawKey) {
        String key = text(rawKey, MAX_KEY, "key", true);
        return operation(id, "press", Map.of("key", key),
            (session, owner) -> workerClient.press(worker(session), owner.get("key").toString()));
    }

    /**
     * 处理历史记录并返回对应结果。
     *
     * @param id 资源标识
     * @param action {@code action}参数
     * @return 处理结果
     */
    public Map<String, Object> history(Long id, String action) {
        String normalized = text(action, 16, "action", true);
        if (!Set.of("back", "forward", "reload").contains(normalized)) {
            throw new ServiceException("浏览器历史动作无效", HttpStatus.BAD_REQUEST);
        }
        return operation(id, normalized, Map.of(),
            (session, ignored) -> workerClient.history(worker(session), normalized));
    }

    /**
     * 处理{@code waitFor}并返回对应结果。
     *
     * @param id 资源标识
     * @param rawCondition {@code rawCondition}参数
     * @param rawValue {@code rawValue}参数
     * @param timeoutMs {@code timeoutMs}参数
     * @return 处理结果
     */
    public Map<String, Object> waitFor(
        Long id, String rawCondition, String rawValue, Integer timeoutMs
    ) {
        String condition = text(rawCondition, 32, "condition", true);
        if (!Set.of("text", "url", "target", "page_state").contains(condition)) {
            throw new ServiceException("不支持的浏览器等待条件", HttpStatus.BAD_REQUEST);
        }
        String value = text(rawValue, 2048, "value", !"page_state".equals(condition));
        int boundedTimeout = timeoutMs == null ? 5_000 : Math.max(100, Math.min(30_000, timeoutMs));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("condition", condition);
        request.put("timeout_ms", boundedTimeout);
        if (value != null) request.put("value", value);
        return operation(id, "wait_for", request,
            (session, ignored) -> workerClient.waitFor(worker(session), condition, value, boundedTimeout));
    }

    /**
     * 获取{@code Option}。
     *
     * @param id 资源标识
     * @param rawSelector {@code rawSelector}参数
     * @param rawValue {@code rawValue}参数
     * @param rawLabel {@code rawLabel}参数
     * @return 处理结果
     */
    public Map<String, Object> selectOption(Long id, String rawSelector, String rawValue, String rawLabel) {
        String selector = text(rawSelector, MAX_SELECTOR, "selector", true);
        String value = text(rawValue, 255, "value", false);
        String label = text(rawLabel, 255, "label", false);
        if (value == null && label == null) {
            throw new ServiceException("value 或 label 至少提供一个", HttpStatus.BAD_REQUEST);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("selector", selector);
        if (value != null) request.put("value", value);
        if (label != null) request.put("label", label);
        return operation(id, "select_option", request,
            (session, ignored) -> workerClient.selectOption(worker(session), selector, value, label));
    }

    /**
     * 处理{@code readVisible}并返回对应结果。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    public Map<String, Object> readVisible(Long id) {
        return operation(id, "read_visible", Map.of(),
            (session, ignored) -> workerClient.readVisible(worker(session)));
    }

    /**
     * 处理{@code drag}并返回对应结果。
     *
     * @param id 资源标识
     * @param rawSource raw数据源参数
     * @param rawTarget {@code rawTarget}参数
     * @return 处理结果
     */
    public Map<String, Object> drag(Long id, String rawSource, String rawTarget) {
        String source = text(rawSource, MAX_SELECTOR, "sourceSelector", true);
        String target = text(rawTarget, MAX_SELECTOR, "targetSelector", true);
        return operation(id, "drag", Map.of("source_selector", source, "target_selector", target),
            (session, ignored) -> workerClient.drag(worker(session), source, target));
    }

    /**
     * 处理{@code download}并返回对应结果。
     *
     * @param id 资源标识
     * @param rawSelector {@code rawSelector}参数
     * @return 处理结果
     */
    public Map<String, Object> download(Long id, String rawSelector) {
        String selector = text(rawSelector, MAX_SELECTOR, "selector", true);
        return operation(id, "download", Map.of("selector", selector),
            (session, ignored) -> publishDownload(workerClient.download(worker(session), selector)));
    }

    /**
     * 处理{@code publishDownload}并返回对应结果。
     *
     * @param response {@code response}参数
     * @return 处理结果
     */
    private Map<String, Object> publishDownload(Map<String, Object> response) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (generatedFileService == null) {
            throw new ServiceException("浏览器下载文件发布服务未启用", 503);
        }
        String encoded = rawText(response, "content_base64", "download_base64");
        if (encoded == null || encoded.isBlank()) {
            throw new ServiceException("浏览器下载结果缺少文件内容", 502);
        }
        String fileName = rawText(response, "filename", "file_name");
        if (fileName == null) fileName = "browser-download";
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            Path temporary = Files.createTempFile("agent-browser-download-", ".bin");
            try {
                Files.write(temporary, bytes);
                GeneratedFileService.PublishedFile published = generatedFileService.publish(temporary, fileName);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("session_id", response.get("session_id"));
                result.put("filename", published.fileName());
                result.put("mime_type", published.mimeType());
                result.put("size", published.size());
                result.put("artifact", published.toolPayload());
                result.put("current_url", response.get("current_url"));
                result.put("page_title", response.get("page_title"));
                return result;
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IllegalArgumentException exception) {
            throw new ServiceException("浏览器下载内容无效", 502);
        } catch (java.io.IOException exception) {
            throw new ServiceException("浏览器下载文件暂存失败", 503);
        }
    }

    /**
 * 处理{@code manualInput}并返回对应结果。
 * Forwards only the current human takeover user's bounded input to Worker. */
    public Map<String, Object> manualInput(
        Long id, String rawEvent, Double x, Double y, String rawKey, String rawText, Double deltaY
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principal();
        requireHuman(principal);
        BrowserSession current = requireOpen(id, principal);
        if (!"human_control".equals(current.handoffStatus())
            || !principal.id().equals(current.handoffUserId())) {
            throw new ServiceException("只有当前接管用户可以操作浏览器页面", HttpStatus.CONFLICT);
        }
        String event = text(rawEvent, 32, "event", true);
        if (!Set.of("mouse_click", "mouse_down", "mouse_move", "mouse_up", "key", "text", "scroll")
            .contains(event)) {
            throw new ServiceException("不支持的人工浏览器事件", HttpStatus.BAD_REQUEST);
        }
        double boundedX = coordinate(x, "x");
        double boundedY = coordinate(y, "y");
        String key = text(rawKey, MAX_KEY, "key", false);
        String inputText = text(rawText, MAX_MANUAL_TEXT, "text", false);
        double boundedDeltaY = deltaY == null ? 0 : Math.max(-2000, Math.min(2000, deltaY));
        if (Set.of("mouse_click", "mouse_down", "mouse_move", "mouse_up").contains(event)
            && (x == null || y == null)) {
            throw new ServiceException("鼠标事件必须提供坐标", HttpStatus.BAD_REQUEST);
        }
        if ("key".equals(event) && key == null) {
            throw new ServiceException("键盘事件必须提供按键", HttpStatus.BAD_REQUEST);
        }
        if ("text".equals(event) && inputText == null) {
            throw new ServiceException("文本事件不能为空", HttpStatus.BAD_REQUEST);
        }
        if ("scroll".equals(event) && deltaY == null) {
            throw new ServiceException("滚动事件必须提供滚动距离", HttpStatus.BAD_REQUEST);
        }
        Map<String, Object> workerRequest = new LinkedHashMap<>();
        workerRequest.put("event", event);
        workerRequest.put("x", boundedX);
        workerRequest.put("y", boundedY);
        if (key != null) workerRequest.put("key", key);
        if (inputText != null) workerRequest.put("text", inputText);
        if ("scroll".equals(event)) workerRequest.put("delta_y", boundedDeltaY);
        Map<String, Object> auditRequest = new LinkedHashMap<>();
        auditRequest.put("event", event);
        auditRequest.put("x", boundedX);
        auditRequest.put("y", boundedY);
        if (key != null) auditRequest.put("key_length", key.length());
        if (inputText != null) auditRequest.put("text_length", inputText.length());
        if ("scroll".equals(event)) auditRequest.put("delta_y", boundedDeltaY);
        return operation(id, "manual_" + event, auditRequest,
            (session, ignored) -> {
                if (!"human_control".equals(session.handoffStatus())
                    || !principal.id().equals(session.handoffUserId())) {
                    throw new ServiceException("浏览器接管状态已变化，请重新接管后操作", HttpStatus.CONFLICT);
                }
                return workerClient.manualInput(worker(session), workerRequest);
            });
    }

    /**
     * 处理{@code coordinate}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private double coordinate(Double value, String label) {
        if (value == null) return 0;
        if (!Double.isFinite(value) || value < 0 || value > 4096) {
            throw new ServiceException(label + "超出浏览器视口范围", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    /**
     * 处理{@code scroll}并返回对应结果。
     *
     * @param id 资源标识
     * @param x {@code x}参数
     * @param y {@code y}参数
     * @param rawSelector {@code rawSelector}参数
     * @return 处理结果
     */
    public Map<String, Object> scroll(Long id, Integer x, Integer y, String rawSelector) {
        String selector = text(rawSelector, MAX_SELECTOR, "selector", false);
        int boundedX = x == null ? 0 : Math.max(-100000, Math.min(100000, x));
        int boundedY = y == null ? 600 : Math.max(-100000, Math.min(100000, y));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("x", boundedX);
        request.put("y", boundedY);
        if (selector != null) request.put("selector", selector);
        return operation(id, "scroll", request,
            (session, owner) -> workerClient.scroll(worker(session), boundedX, boundedY, selector));
    }

    /**
     * 处理{@code hover}并返回对应结果。
     *
     * @param id 资源标识
     * @param rawSelector {@code rawSelector}参数
     * @return 处理结果
     */
    public Map<String, Object> hover(Long id, String rawSelector) {
        String selector = text(rawSelector, MAX_SELECTOR, "selector", true);
        return operation(id, "hover", Map.of("selector", selector),
            (session, owner) -> workerClient.hover(worker(session), selector));
    }

    /**
     * 处理{@code upload}并返回对应结果。
     *
     * @param id 资源标识
     * @param rawSelector {@code rawSelector}参数
     * @param files {@code files}参数
     * @return 处理结果
     */
    public Map<String, Object> upload(Long id, String rawSelector, List<String> files) {
        String selector = text(rawSelector, MAX_SELECTOR, "selector", true);
        if (files == null || files.isEmpty() || files.size() > 10) {
            throw new ServiceException("files 数量必须在 1 到 10 之间", HttpStatus.BAD_REQUEST);
        }
        List<String> boundedFiles = files.stream()
            .map(value -> text(value, 512, "file", true)).toList();
        return operation(id, "upload", Map.of("selector", selector, "files", boundedFiles),
            (session, owner) -> workerClient.upload(worker(session), selector, boundedFiles));
    }

    /**
     * 处理{@code tabs}并返回对应结果。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    public Map<String, Object> tabs(Long id) {
        return operation(id, "tabs", Map.of(),
            (session, owner) -> workerClient.tabs(worker(session)));
    }

    /**
     * 处理{@code openTab}并返回对应结果。
     *
     * @param id 资源标识
     * @param rawUrl {@code rawUrl}参数
     * @return 处理结果
     */
    public Map<String, Object> openTab(Long id, String rawUrl) {
        String normalized = url(rawUrl, false);
        Map<String, Object> request = normalized == null ? Map.of() : Map.of("url", normalized);
        return operation(id, "tab_open", request,
            (session, owner) -> workerClient.openTab(worker(session), normalized));
    }

    /**
     * 处理{@code activateTab}并返回对应结果。
     *
     * @param id 资源标识
     * @param tabId 资源标识
     * @return 处理结果
     */
    public Map<String, Object> activateTab(Long id, String tabId) {
        String value = text(tabId, MAX_TAB_ID, "tabId", true);
        return operation(id, "tab_activate", Map.of("tab_id", value),
            (session, owner) -> workerClient.activateTab(worker(session), value));
    }

    /**
     * 处理{@code closeTab}并返回对应结果。
     *
     * @param id 资源标识
     * @param tabId 资源标识
     * @return 处理结果
     */
    public Map<String, Object> closeTab(Long id, String tabId) {
        String value = text(tabId, MAX_TAB_ID, "tabId", true);
        return operation(id, "tab_close", Map.of("tab_id", value),
            (session, owner) -> workerClient.closeTab(worker(session), value));
    }

    /**
     * 处理事件相关逻辑。
     *
     * @param sessionId 资源标识
     * @param ownerId 资源标识
     * @param type 业务类型
     * @param status 目标状态
     * @param request 请求参数
     * @param response {@code response}参数
     * @param error {@code error}参数
     * @param now {@code now}参数
     */
    private void event(Long sessionId, Long ownerId, String type, String status,
                       Map<String, Object> request, Map<String, Object> response,
                       String error, LocalDateTime now) {
        repository.insertEvent(
            idGenerator.nextId(), sessionId, ownerId, type, status,
            json(request), json(auditResponse(response)), bounded(error, 2000), now
        );
    }

    /**
     * 处理{@code view}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> view(BrowserSession value) {
        return view(value, null);
    }

    /**
     * 处理{@code view}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param worker 工作进程参数
     * @return 处理结果
     */
    private Map<String, Object> view(BrowserSession value, Map<String, Object> worker) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", value.id());
        result.put("ownerId", value.ownerId());
        result.put("owner_id", value.ownerId());
        result.put("sessionKey", value.sessionKey());
        result.put("session_key", value.sessionKey());
        result.put("workerSessionId", value.workerSessionId());
        result.put("worker_session_id", value.workerSessionId());
        result.put("profileKey", value.profileKey());
        result.put("profile_key", value.profileKey());
        result.put("status", value.status());
        result.put("currentUrl", value.currentUrl());
        result.put("current_url", value.currentUrl());
        result.put("pageTitle", value.pageTitle());
        result.put("page_title", value.pageTitle());
        result.put("createdAt", value.createdAt());
        result.put("created_at", value.createdAt());
        result.put("updatedAt", value.updatedAt());
        result.put("updated_at", value.updatedAt());
        result.put("closedAt", value.closedAt());
        result.put("closed_at", value.closedAt());
        result.put("activeTabId", value.activeTabId());
        result.put("active_tab_id", value.activeTabId());
        String handoffStatus = value.handoffStatus() == null ? "none" : value.handoffStatus();
        result.put("handoffStatus", handoffStatus);
        result.put("handoff_status", handoffStatus);
        result.put("handoffReason", value.handoffReason());
        result.put("handoff_reason", value.handoffReason());
        result.put("handoffUserId", value.handoffUserId());
        result.put("handoff_user_id", value.handoffUserId());
        result.put("handoffRequestedAt", value.handoffRequestedAt());
        result.put("handoff_requested_at", value.handoffRequestedAt());
        result.put("handoffStartedAt", value.handoffStartedAt());
        result.put("handoff_started_at", value.handoffStartedAt());
        result.put("handoffReturnedAt", value.handoffReturnedAt());
        result.put("handoff_returned_at", value.handoffReturnedAt());
        if (value.tabStateJson() != null) {
            try {
                result.put("tabs", jsonMapper.readValue(value.tabStateJson(), Object.class));
            } catch (RuntimeException ignored) {
                result.put("tabs", List.of());
            }
        }
        if (worker != null) {
            result.put("worker", worker);
            Map<String, Object> snapshot = snapshot(worker);
            if (snapshot != null) result.put("snapshot", snapshot);
        }
        return result;
    }

    /**
     * 处理快照并返回对应结果。
     *
     * @param worker 工作进程参数
     * @return 处理结果
     */
    private Map<String, Object> snapshot(Map<String, Object> worker) {
        if (worker == null) return null;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sessionId", rawText(worker, "session_id", "sessionId"));
        value.put("url", rawText(worker, "url", "current_url", "currentUrl"));
        value.put("title", rawText(worker, "title", "page_title", "pageTitle"));
        value.put("text", bounded(rawText(worker, "text"), 200_000));
        String screenshot = rawText(worker, "screenshot_base64", "screenshotBase64", "screenshot_data_url");
        if (screenshot != null && screenshot.startsWith("data:image/")) {
            int comma = screenshot.indexOf(',');
            screenshot = comma >= 0 ? screenshot.substring(comma + 1) : null;
        }
        value.put("screenshotBase64", screenshot);
        value.put("capturedAt", rawText(worker, "captured_at", "capturedAt"));
        value.put("activeTabId", rawText(worker, "active_tab_id", "activeTabId"));
        Object tabs = worker.get("tabs");
        if (tabs instanceof List<?> list) {
            value.put("tabs", list.stream().filter(item -> item instanceof Map<?, ?>)
                .map(item -> {
                    Map<?, ?> raw = (Map<?, ?>) item;
                    Map<String, Object> tab = new LinkedHashMap<>();
                    Object tabId = raw.containsKey("tab_id") ? raw.get("tab_id") : raw.get("tabId");
                    tab.put("tabId", tabId);
                    tab.put("url", raw.get("url"));
                    tab.put("title", raw.get("title"));
                    tab.put("active", Boolean.TRUE.equals(raw.get("active")));
                    return tab;
                }).toList());
        }
        return value;
    }

    /**
     * 处理{@code rawText}并返回对应结果。
     *
     * @param source 数据源参数
     * @param keys {@code keys}参数
     * @return 处理结果
     */
    private String rawText(Map<String, Object> source, String... keys) {
        if (source == null) return null;
        for (String key : keys) {
            Object value = source.get(key);
            if (value instanceof String text && !text.isBlank()) return text.strip();
        }
        return null;
    }

    /**
     * 处理工作进程并返回对应结果。
     *
     * @param session 会话参数
     * @return 处理结果
     */
    private String worker(BrowserSession session) {
        return session.workerSessionId() == null ? session.sessionKey() : session.workerSessionId();
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @param max {@code max}参数
     * @param label {@code label}参数
     * @param required {@code required}参数
     * @return 处理结果
     */
    private String text(String raw, int max, String label, boolean required) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (raw == null) {
            if (required) throw new ServiceException(label + "不能为空", HttpStatus.BAD_REQUEST);
            return null;
        }
        String value = raw.replace('\0', ' ').strip();
        if (value.isBlank() && required) {
            throw new ServiceException(label + "不能为空", HttpStatus.BAD_REQUEST);
        }
        if (value.length() > max) throw new ServiceException(label + "超过长度限制", HttpStatus.BAD_REQUEST);
        return value.isBlank() ? null : value;
    }

    /**
     * 处理{@code url}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @param required {@code required}参数
     * @return 处理结果
     */
    private String url(String raw, boolean required) {
        String value = text(raw, MAX_URL, "url", required);
        if (value == null) return null;
        try {
            java.net.URI uri = endpointPolicy.normalize(value);
            endpointPolicy.validateNetworkTarget(uri);
            return uri.toASCIIString();
        } catch (RuntimeException exception) {
            if (exception instanceof ServiceException serviceException) throw serviceException;
            throw new ServiceException("url 必须是合法的 HTTP/HTTPS 地址", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 处理{@code firstText}并返回对应结果。
     *
     * @param source 数据源参数
     * @param keys {@code keys}参数
     * @return 处理结果
     */
    private String firstText(Map<String, Object> source, String... keys) {
        if (source == null) return null;
        for (String key : keys) {
            Object value = source.get(key);
            if (value instanceof String text && !text.isBlank()) return bounded(text.strip(), MAX_URL);
        }
        return null;
    }

    /**
     * 处理{@code json}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String json(Object value) {
        if (value == null) return null;
        try {
            String serialized = jsonMapper.writeValueAsString(value);
            return serialized.length() <= MAX_EVENT_JSON
                ? serialized : "{\"truncated\":true}";
        } catch (RuntimeException exception) {
            return "{\"serialization_error\":true}";
        }
    }

    /**
     * 处理{@code tabJson}并返回对应结果。
     *
     * @param response {@code response}参数
     * @return 处理结果
     */
    private String tabJson(Map<String, Object> response) {
        if (response == null || response.get("tabs") == null) return null;
        return json(response.get("tabs"));
    }

    /**
     * 处理审计Request并返回对应结果。
     *
     * @param eventType 业务类型
     * @param request 请求参数
     * @return 处理结果
     */
    private Map<String, Object> auditRequest(String eventType, Map<String, Object> request) {
        if (!"fill".equals(eventType) || request == null || !request.containsKey("value")) {
            return request;
        }
        Map<String, Object> safe = new LinkedHashMap<>(request);
        Object value = safe.remove("value");
        safe.put("value_length", value == null ? 0 : value.toString().length());
        safe.put("value_redacted", true);
        return safe;
    }

    /**
     * 处理审计Response并返回对应结果。
     *
     * @param response {@code response}参数
     * @return 处理结果
     */
    private Map<String, Object> auditResponse(Map<String, Object> response) {
        if (response == null) return null;
        Map<String, Object> safe = new LinkedHashMap<>(response);
        safe.remove("screenshot_data_url");
        safe.remove("screenshot_base64");
        safe.remove("content_base64");
        safe.remove("download_base64");
        safe.remove("html");
        safe.remove("text");
        return safe;
    }

    /**
     * 处理消息并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    private String message(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    /**
     * 判断工作进程Unavailable是否满足要求。
     *
     * @param exception {@code exception}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isWorkerUnavailable(RuntimeException exception) {
        if (!(exception instanceof ServiceException serviceException)) return false;
        Integer code = serviceException.getCode();
        return code != null && (code == 404 || code >= 500);
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @return 处理结果
     */
    private String bounded(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 定义浏览器操作相关能力的服务契约。
     */
    @FunctionalInterface
    private interface BrowserOperation {
        /**
         * 处理{@code call}并返回对应结果。
         *
         * @param session 会话参数
         * @param request 请求参数
         * @return 处理结果
         */
        Map<String, Object> call(BrowserSession session, Map<String, Object> request);
    }
}
