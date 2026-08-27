package group.aitools.nhs.platform.memory.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.memory.service.MemoryApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供平台记忆相关的 HTTP 接口，并负责请求校验与结果返回。
 */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/memories")
public class PlatformMemoryController {

    private final MemoryApplicationService service;

    /**
     * 创建 {@code PlatformMemoryController} 实例并初始化所需依赖。
     *
     * @param service {@code service}参数
     */
    public PlatformMemoryController(MemoryApplicationService service) {
        this.service = service;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param search {@code search}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/{scopeType}/{scopeId}")
    public R<List<MemoryView>> list(
        @PathVariable String scopeType,
        @PathVariable @Positive Long scopeId,
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit
    ) {
        return R.ok(service.list(scopeType, scopeId, search, limit));
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{scopeType}/{scopeId}")
    public R<MemoryView> create(
        @PathVariable String scopeType,
        @PathVariable @Positive Long scopeId,
        @Valid @RequestBody CreateMemoryRequest request
    ) {
        return R.ok(service.create(scopeType, scopeId, request));
    }

    /**
     * 更新{@code update}。
     *
     * @param memoryId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{memoryId}")
    public R<MemoryView> update(
        @PathVariable @Positive Long memoryId,
        @Valid @RequestBody UpdateMemoryRequest request
    ) {
        return R.ok(service.update(memoryId, request));
    }

    /**
     * 处理{@code review}并返回对应结果。
     *
     * @param memoryId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{memoryId}/review")
    public R<MemoryView> review(
        @PathVariable @Positive Long memoryId,
        @Valid @RequestBody ReviewMemoryRequest request
    ) {
        return R.ok(service.review(memoryId, request));
    }

    /**
     * 删除{@code delete}。
     *
     * @param memoryId 资源标识
     * @param expectedRevision {@code expectedRevision}参数
     * @return 处理结果
     */
    @DeleteMapping("/{memoryId}")
    public R<Void> delete(
        @PathVariable @Positive Long memoryId,
        @RequestParam @Positive Long expectedRevision
    ) {
        service.delete(memoryId, expectedRevision);
        return R.ok();
    }
}
