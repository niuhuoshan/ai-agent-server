package group.aitools.nhs.platform.browser.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import group.aitools.nhs.platform.browser.service.BrowserSessionApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 提供平台浏览器相关的 HTTP 接口，并负责请求校验与结果返回。
 * Owner-scoped browser control API backed by an isolated HTTP Worker. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/browser")
public class PlatformBrowserController {

    private final BrowserSessionApplicationService service;

    public PlatformBrowserController(BrowserSessionApplicationService service) {
        this.service = service;
    }

    /**
     * 处理{@code open}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/sessions")
    public R<Map<String, Object>> open(
        @Valid @RequestBody(required = false) OpenBrowserSessionRequest request
    ) {
        return R.ok(service.open(
            request == null ? null : request.profileKey(),
            request == null ? null : request.startUrl()
        ));
    }

    /**
     * 查询{@code list}列表。
     *
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/sessions")
    public R<List<Map<String, Object>>> list(
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        return R.ok(service.list(limit));
    }

    /**
     * 处理健康状态并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        return R.ok(service.workerHealth());
    }

    /**
     * 清理或重置{@code Profiles}。
     *
     * @return 处理结果
     */
    @DeleteMapping("/profiles/clear")
    public R<Map<String, Object>> clearProfiles() {
        return R.ok(service.clearOwnedBrowserProfiles());
    }

    /**
     * 获取{@code get}。
     *
     * @param sessionId 资源标识
     * @return 处理结果
     */
    @GetMapping("/sessions/{sessionId}")
    public R<Map<String, Object>> get(@PathVariable @Positive Long sessionId) {
        return R.ok(service.get(sessionId));
    }

    /**
     * 处理{@code handoff}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @return 处理结果
     */
    @GetMapping("/sessions/{sessionId}/handoff")
    public R<Map<String, Object>> handoff(@PathVariable @Positive Long sessionId) {
        return R.ok(service.get(sessionId));
    }

    /**
     * 处理{@code requestHandoff}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/handoff/request")
    public R<Map<String, Object>> requestHandoff(
        @PathVariable @Positive Long sessionId,
        @Valid @RequestBody(required = false) BrowserHandoffRequest request
    ) {
        return R.ok(service.requestHandoff(sessionId, request == null ? null : request.reason()));
    }

    /**
     * 处理{@code takeover}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/handoff/takeover")
    public R<Map<String, Object>> takeover(@PathVariable @Positive Long sessionId) {
        return R.ok(service.takeHandoff(sessionId));
    }

    /**
     * 处理{@code returnToAi}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/handoff/return")
    public R<Map<String, Object>> returnToAi(@PathVariable @Positive Long sessionId) {
        return R.ok(service.returnHandoff(sessionId));
    }

    /**
     * 处理{@code navigate}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/navigate")
    public R<Map<String, Object>> navigate(
        @PathVariable @Positive Long sessionId,
        @Valid @RequestBody NavigateBrowserRequest request
    ) {
        return R.ok(service.navigate(sessionId, request.url()));
    }

    /**
     * 处理快照并返回对应结果。
     *
     * @param sessionId 资源标识
     * @return 处理结果
     */
    @GetMapping("/sessions/{sessionId}/snapshot")
    public R<Map<String, Object>> snapshot(@PathVariable @Positive Long sessionId) {
        return R.ok(service.snapshot(sessionId));
    }

    /**
     * 处理{@code click}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/click")
    public R<Map<String, Object>> click(
        @PathVariable @Positive Long sessionId,
        @Valid @RequestBody BrowserClickRequest request
    ) {
        return R.ok(service.click(sessionId, request.selector()));
    }

    /**
     * 处理{@code fill}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/fill")
    public R<Map<String, Object>> fill(
        @PathVariable @Positive Long sessionId,
        @Valid @RequestBody BrowserFillRequest request
    ) {
        return R.ok(service.fill(sessionId, request.selector(), request.value()));
    }

    /**
     * 处理{@code press}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/press")
    public R<Map<String, Object>> press(
        @PathVariable @Positive Long sessionId,
        @Valid @RequestBody BrowserPressRequest request
    ) {
        return R.ok(service.press(sessionId, request.key()));
    }

    /**
     * 处理{@code back}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/back")
    public R<Map<String, Object>> back(@PathVariable @Positive Long sessionId) {
        return R.ok(service.history(sessionId, "back"));
    }

    /**
     * 处理{@code forward}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/forward")
    public R<Map<String, Object>> forward(@PathVariable @Positive Long sessionId) {
        return R.ok(service.history(sessionId, "forward"));
    }

    /**
     * 处理{@code reload}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/reload")
    public R<Map<String, Object>> reload(@PathVariable @Positive Long sessionId) {
        return R.ok(service.history(sessionId, "reload"));
    }

    /**
     * 处理{@code waitFor}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/wait-for")
    public R<Map<String, Object>> waitFor(
        @PathVariable @Positive Long sessionId,
        @Valid @RequestBody BrowserWaitRequest request
    ) {
        return R.ok(service.waitFor(sessionId, request.condition(), request.value(), request.timeoutMs()));
    }

    /**
     * 获取{@code Option}。
     *
     * @param sessionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/select-option")
    public R<Map<String, Object>> selectOption(
        @PathVariable @Positive Long sessionId,
        @Valid @RequestBody BrowserSelectOptionRequest request
    ) {
        return R.ok(service.selectOption(sessionId, request.selector(), request.value(), request.label()));
    }

    /**
     * 处理{@code readVisible}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @return 处理结果
     */
    @GetMapping("/sessions/{sessionId}/read-visible")
    public R<Map<String, Object>> readVisible(@PathVariable @Positive Long sessionId) {
        return R.ok(service.readVisible(sessionId));
    }

    /**
     * 处理{@code drag}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/drag")
    public R<Map<String, Object>> drag(
        @PathVariable @Positive Long sessionId,
        @Valid @RequestBody BrowserDragRequest request
    ) {
        return R.ok(service.drag(sessionId, request.sourceSelector(), request.targetSelector()));
    }

    /**
     * 处理{@code download}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/download")
    public R<Map<String, Object>> download(
        @PathVariable @Positive Long sessionId,
        @Valid @RequestBody BrowserDownloadRequest request
    ) {
        return R.ok(service.download(sessionId, request.selector()));
    }

    /**
     * 处理{@code manualInput}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/manual-input")
    public R<Map<String, Object>> manualInput(
        @PathVariable @Positive Long sessionId,
        @Valid @RequestBody BrowserManualInputRequest request
    ) {
        return R.ok(service.manualInput(
            sessionId, request.event(), request.x(), request.y(), request.key(), request.text(), request.deltaY()
        ));
    }

    /**
     * 处理{@code scroll}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/scroll")
    public R<Map<String, Object>> scroll(
        @PathVariable @Positive Long sessionId,
        @Valid @RequestBody BrowserScrollRequest request
    ) {
        return R.ok(service.scroll(sessionId, request.x(), request.y(), request.selector()));
    }

    /**
     * 处理{@code hover}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/hover")
    public R<Map<String, Object>> hover(
        @PathVariable @Positive Long sessionId,
        @Valid @RequestBody BrowserHoverRequest request
    ) {
        return R.ok(service.hover(sessionId, request.selector()));
    }

    /**
     * 处理{@code upload}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/upload")
    public R<Map<String, Object>> upload(
        @PathVariable @Positive Long sessionId,
        @Valid @RequestBody BrowserUploadRequest request
    ) {
        return R.ok(service.upload(sessionId, request.selector(), request.files()));
    }

    /**
     * 处理{@code tabs}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @return 处理结果
     */
    @GetMapping("/sessions/{sessionId}/tabs")
    public R<Map<String, Object>> tabs(@PathVariable @Positive Long sessionId) {
        return R.ok(service.tabs(sessionId));
    }

    /**
     * 处理{@code openTab}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/tabs")
    public R<Map<String, Object>> openTab(
        @PathVariable @Positive Long sessionId,
        @Valid @RequestBody(required = false) BrowserTabOpenRequest request
    ) {
        return R.ok(service.openTab(sessionId, request == null ? null : request.url()));
    }

    /**
     * 处理{@code activateTab}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param tabId 资源标识
     * @return 处理结果
     */
    @PostMapping("/sessions/{sessionId}/tabs/{tabId}/activate")
    public R<Map<String, Object>> activateTab(
        @PathVariable @Positive Long sessionId,
        @PathVariable @Size(max = 255) String tabId
    ) {
        return R.ok(service.activateTab(sessionId, tabId));
    }

    /**
     * 处理{@code closeTab}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param tabId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/sessions/{sessionId}/tabs/{tabId}")
    public R<Map<String, Object>> closeTab(
        @PathVariable @Positive Long sessionId,
        @PathVariable @Size(max = 255) String tabId
    ) {
        return R.ok(service.closeTab(sessionId, tabId));
    }

    /**
     * 处理{@code close}并返回对应结果。
     *
     * @param sessionId 资源标识
     * @param destroyProfile destroy配置档案参数
     * @return 处理结果
     */
    @DeleteMapping("/sessions/{sessionId}")
    public R<Map<String, Object>> close(
        @PathVariable @Positive Long sessionId,
        @RequestParam(defaultValue = "false") boolean destroyProfile
    ) {
        return R.ok(service.close(sessionId, destroyProfile));
    }
}
