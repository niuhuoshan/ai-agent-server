package group.aitools.nhs.platform.project.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.project.service.ProjectApplicationService;
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

/**
 * 提供平台项目相关的 HTTP 接口，并负责请求校验与结果返回。
 * Project metadata, lifecycle and explicit membership endpoints. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/projects")
public class PlatformProjectController {

    private final ProjectApplicationService projectService;

    public PlatformProjectController(ProjectApplicationService projectService) {
        this.projectService = projectService;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param status 目标状态
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<ProjectView>> list(
        @RequestParam(required = false) @Pattern(regexp = "active|suspended|archived") String status,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(projectService.list(status, limit));
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping
    public R<ProjectMutationResult> create(@Valid @RequestBody CreateProjectRequest request) {
        return R.ok(projectService.create(request));
    }

    /**
     * 获取{@code get}。
     *
     * @param projectId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{projectId}")
    public R<ProjectView> get(@PathVariable @Positive Long projectId) {
        return R.ok(projectService.get(projectId));
    }

    /**
     * 更新{@code update}。
     *
     * @param projectId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{projectId}")
    public R<ProjectView> update(
        @PathVariable @Positive Long projectId,
        @Valid @RequestBody UpdateProjectRequest request
    ) {
        return R.ok(projectService.update(projectId, request));
    }

    /**
     * 更新{@code Status}。
     *
     * @param projectId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PatchMapping("/{projectId}/status")
    public R<ProjectView> updateStatus(
        @PathVariable @Positive Long projectId,
        @Valid @RequestBody UpdateProjectStatusRequest request
    ) {
        return R.ok(projectService.updateStatus(projectId, request.status()));
    }

    /**
     * 处理{@code members}并返回对应结果。
     *
     * @param projectId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/{projectId}/members")
    public R<List<ProjectMemberView>> members(
        @PathVariable @Positive Long projectId,
        @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return R.ok(projectService.members(projectId, limit));
    }

    /**
     * 处理{@code putMember}并返回对应结果。
     *
     * @param projectId 资源标识
     * @param userId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{projectId}/members/{userId}")
    public R<ProjectMemberView> putMember(
        @PathVariable @Positive Long projectId,
        @PathVariable @Positive Long userId,
        @Valid @RequestBody PutProjectMemberRequest request
    ) {
        return R.ok(projectService.putMember(projectId, userId, request.role()));
    }

    /**
     * 删除{@code Member}。
     *
     * @param projectId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/{projectId}/members/{userId}")
    public R<Void> removeMember(
        @PathVariable @Positive Long projectId,
        @PathVariable @Positive Long userId
    ) {
        projectService.removeMember(projectId, userId);
        return R.ok();
    }
}
