package group.aitools.nhs.platform.task.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.platform.task.service.TaskApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供平台任务相关的 HTTP 接口，并负责请求校验与结果返回。
 * Visibility-aware task query endpoints. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/tasks")
public class PlatformTaskController {

    private final TaskQueryService taskQueryService;
    private final TaskApplicationService taskApplicationService;

    public PlatformTaskController(
        TaskQueryService taskQueryService,
        TaskApplicationService taskApplicationService
    ) {
        this.taskQueryService = taskQueryService;
        this.taskApplicationService = taskApplicationService;
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping
    public R<TaskMutationResult> create(@Valid @RequestBody CreateTaskRequest request) {
        return R.ok(taskApplicationService.create(request));
    }

    /**
     * 查询{@code list}列表。
     *
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<TaskView>> list(
        @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit
    ) {
        return R.ok(taskQueryService.list(limit));
    }

    /**
     * 获取{@code get}。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{taskId}")
    public R<TaskView> get(@PathVariable @Positive Long taskId) {
        return R.ok(taskQueryService.get(taskId));
    }

    /**
     * 处理{@code visibility}并返回对应结果。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{taskId}/visibility")
    public R<TaskVisibilityView> visibility(@PathVariable @Positive Long taskId) {
        return R.ok(taskQueryService.visibility(taskId));
    }

    /**
     * 更新{@code update}。
     *
     * @param taskId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{taskId}")
    public R<TaskMutationResult> update(
        @PathVariable @Positive Long taskId,
        @Valid @RequestBody UpdateTaskRequest request
    ) {
        return R.ok(taskApplicationService.update(taskId, request));
    }

    /**
     * 更新{@code Status}。
     *
     * @param taskId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PatchMapping("/{taskId}/status")
    public R<TaskView> updateStatus(
        @PathVariable @Positive Long taskId,
        @Valid @RequestBody UpdateTaskStatusRequest request
    ) {
        return R.ok(taskApplicationService.updateStatus(taskId, request.status()));
    }

    /**
     * 处理{@code versions}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/{taskId}/versions")
    public R<List<TaskVersionView>> versions(
        @PathVariable @Positive Long taskId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        return R.ok(taskApplicationService.versions(taskId, limit));
    }

    /**
     * 处理版本并返回对应结果。
     *
     * @param taskId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{taskId}/versions/{versionId}")
    public R<TaskVersionView> version(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long versionId
    ) {
        return R.ok(taskApplicationService.version(taskId, versionId));
    }

    /**
     * 处理{@code participants}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/{taskId}/participants")
    public R<List<TaskParticipantView>> participants(
        @PathVariable @Positive Long taskId,
        @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return R.ok(taskApplicationService.participants(taskId, limit));
    }

    /**
     * 处理{@code putParticipant}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param userId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{taskId}/participants/{userId}")
    public R<TaskParticipantView> putParticipant(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long userId,
        @Valid @RequestBody PutTaskParticipantRequest request
    ) {
        return R.ok(taskApplicationService.putParticipant(taskId, userId, request.type()));
    }

    /**
     * 删除{@code Participant}。
     *
     * @param taskId 资源标识
     * @param userId 资源标识
     * @param type 业务类型
     * @return 处理结果
     */
    @DeleteMapping("/{taskId}/participants/{userId}/{type}")
    public R<Void> removeParticipant(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long userId,
        @PathVariable @jakarta.validation.constraints.Pattern(
            regexp = "assignee|collaborator|acceptor|watcher"
        ) String type
    ) {
        taskApplicationService.removeParticipant(taskId, userId, type);
        return R.ok();
    }

    /**
     * 处理{@code resources}并返回对应结果。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{taskId}/resources")
    public R<List<TaskResourceView>> resources(@PathVariable @Positive Long taskId) {
        return R.ok(taskApplicationService.resources(taskId));
    }

    /**
     * 处理{@code accessRules}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/{taskId}/access-rules")
    public R<List<TaskAccessRuleView>> accessRules(
        @PathVariable @Positive Long taskId,
        @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return R.ok(taskApplicationService.accessRules(taskId, limit));
    }

    /**
     * 处理{@code putAccessRule}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{taskId}/access-rules")
    public R<TaskAccessRuleView> putAccessRule(
        @PathVariable @Positive Long taskId,
        @Valid @RequestBody PutTaskAccessRuleRequest request
    ) {
        return R.ok(taskApplicationService.putAccessRule(taskId, request));
    }

    /**
     * 删除{@code AccessRule}。
     *
     * @param taskId 资源标识
     * @param ruleId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/{taskId}/access-rules/{ruleId}")
    public R<Void> removeAccessRule(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long ruleId
    ) {
        taskApplicationService.removeAccessRule(taskId, ruleId);
        return R.ok();
    }
}
