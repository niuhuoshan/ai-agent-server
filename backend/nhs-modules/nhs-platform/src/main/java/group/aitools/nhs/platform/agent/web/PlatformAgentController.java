package group.aitools.nhs.platform.agent.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import group.aitools.nhs.platform.agent.service.AgentApplicationService;
import group.aitools.nhs.platform.agent.service.AgentExecutionHistoryService;
import group.aitools.nhs.platform.agent.service.AgentWelcomeCardService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 提供平台智能体相关的 HTTP 接口，并负责请求校验与结果返回。
 * Agent identity, draft and immutable version management endpoints. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping({"/platform/agents", "/api/portal/agents"})
public class PlatformAgentController {

    private final AgentApplicationService agentService;
    private final AgentExecutionHistoryService executionHistoryService;
    private final AgentWelcomeCardService welcomeCardService;

    public PlatformAgentController(
        AgentApplicationService agentService,
        AgentExecutionHistoryService executionHistoryService,
        AgentWelcomeCardService welcomeCardService
    ) {
        this.agentService = agentService;
        this.executionHistoryService = executionHistoryService;
        this.welcomeCardService = welcomeCardService;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param search {@code search}参数
     * @param includeArchived {@code includeArchived}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<AgentView>> list(
        @RequestParam(required = false) @Size(max = 128) String search,
        @RequestParam(defaultValue = "false") boolean includeArchived,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(agentService.list(search, includeArchived, limit));
    }

    /**
     * 处理{@code allowed}并返回对应结果。
     *
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/allowed")
    public R<List<AgentView>> allowed(
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(agentService.allowed(limit));
    }

    /**
     * 获取{@code get}。
     *
     * @param agentId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{agentId}")
    public R<AgentView> get(@PathVariable @Positive Long agentId) {
        return R.ok(agentService.get(agentId));
    }

    /**
 * 处理嵌入式会话Access并返回对应结果。
 * Nhs-compatible EmbedChat URL deep-link access check (id or agent key). */
    @GetMapping("/{agentKey}/embed-access")
    public R<AgentView> embedAccess(@PathVariable String agentKey) {
        return R.ok(agentService.embedAccess(agentKey));
    }

    /**
     * 处理{@code executions}并返回对应结果。
     *
     * @param agentId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/{agentId}/executions")
    public R<List<AgentExecutionHistoryView>> executions(
        @PathVariable @Positive Long agentId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        return R.ok(executionHistoryService.list(agentId, limit));
    }

    /**
 * 处理{@code welcomeCards}并返回对应结果。
 * Nhs runtime welcome cards for the active published version. */
    @GetMapping("/{agentId}/welcome-cards")
    public R<Map<String, Object>> welcomeCards(@PathVariable @Positive Long agentId) {
        return R.ok(Map.of("cards", welcomeCardService.list(agentId)));
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping
    public R<AgentView> create(@Valid @RequestBody CreateAgentRequest request) {
        return R.ok(agentService.create(request));
    }

    /**
     * 处理{@code reorder}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/reorder")
    public R<Void> reorder(@Valid @RequestBody ReorderAgentsRequest request) {
        agentService.reorder(request.items());
        return R.ok();
    }

    /**
     * 处理{@code onboard}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/onboarding")
    public R<AgentOnboardingResult> onboard(@Valid @RequestBody AgentOnboardingRequest request) {
        return R.ok(agentService.onboard(request));
    }

    /**
     * 更新{@code update}。
     *
     * @param agentId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{agentId}")
    public R<AgentView> update(
        @PathVariable @Positive Long agentId,
        @Valid @RequestBody UpdateAgentRequest request
    ) {
        return R.ok(agentService.update(agentId, request));
    }

    /**
     * 更新{@code Status}。
     *
     * @param agentId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PatchMapping("/{agentId}/status")
    public R<AgentView> updateStatus(
        @PathVariable @Positive Long agentId,
        @Valid @RequestBody UpdateAgentStatusRequest request
    ) {
        return R.ok(agentService.updateStatus(agentId, request.status()));
    }

    /**
     * 删除{@code delete}。
     *
     * @param agentId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/{agentId}")
    public R<Void> delete(@PathVariable @Positive Long agentId) {
        agentService.delete(agentId);
        return R.ok();
    }

    /**
     * 处理{@code versions}并返回对应结果。
     *
     * @param agentId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{agentId}/versions")
    public R<List<AgentVersionView>> versions(@PathVariable @Positive Long agentId) {
        return R.ok(agentService.versions(agentId));
    }

    /**
     * 创建并保存版本。
     *
     * @param agentId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{agentId}/versions")
    public R<AgentVersionView> createVersion(
        @PathVariable @Positive Long agentId,
        @Valid @RequestBody SaveAgentVersionRequest request
    ) {
        return R.ok(agentService.createVersion(agentId, request));
    }

    /**
     * 更新版本。
     *
     * @param agentId 资源标识
     * @param versionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{agentId}/versions/{versionId}")
    public R<AgentVersionView> updateVersion(
        @PathVariable @Positive Long agentId,
        @PathVariable @Positive Long versionId,
        @Valid @RequestBody SaveAgentVersionRequest request
    ) {
        return R.ok(agentService.updateVersion(agentId, versionId, request));
    }

    /**
     * 处理clone版本并返回对应结果。
     *
     * @param agentId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @PostMapping("/{agentId}/versions/{versionId}/clone")
    public R<AgentVersionView> cloneVersion(
        @PathVariable @Positive Long agentId,
        @PathVariable @Positive Long versionId
    ) {
        return R.ok(agentService.cloneVersion(agentId, versionId));
    }

    /**
     * 删除版本。
     *
     * @param agentId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/{agentId}/versions/{versionId}")
    public R<Void> deleteVersion(
        @PathVariable @Positive Long agentId,
        @PathVariable @Positive Long versionId
    ) {
        agentService.deleteVersion(agentId, versionId);
        return R.ok();
    }

    /**
     * 处理{@code publish}并返回对应结果。
     *
     * @param agentId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @PostMapping("/{agentId}/versions/{versionId}/publish")
    public R<AgentVersionPublishResult> publish(
        @PathVariable @Positive Long agentId,
        @PathVariable @Positive Long versionId
    ) {
        return R.ok(agentService.publish(agentId, versionId));
    }

    /**
     * 处理archive版本并返回对应结果。
     *
     * @param agentId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @PostMapping("/{agentId}/versions/{versionId}/archive")
    public R<AgentVersionView> archiveVersion(
        @PathVariable @Positive Long agentId,
        @PathVariable @Positive Long versionId
    ) {
        return R.ok(agentService.archiveVersion(agentId, versionId));
    }

    /**
     * 处理{@code activeConfig}并返回对应结果。
     *
     * @param agentId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{agentId}/active-config")
    public R<AgentVersionView> activeConfig(@PathVariable @Positive Long agentId) {
        return R.ok(agentService.activeConfig(agentId));
    }
}
