package group.aitools.nhs.platform.connector.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import group.aitools.nhs.platform.connector.service.ToolCatalogService;
import group.aitools.nhs.platform.connector.service.ToolOnlineTestService;
import group.aitools.nhs.platform.connector.service.BuiltinToolCatalog;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 提供平台工具相关的 HTTP 接口，并负责请求校验与结果返回。
 * Versioned tool catalog and permission-filtered discovery. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping({"/platform/tools", "/api/portal/tools"})
public class PlatformToolController {

    private static final String ALL_TYPES = "builtin|api|mcp|search|sql|sandbox";
    private final ToolCatalogService service;
    private final ToolOnlineTestService testService;

    public PlatformToolController(ToolCatalogService service, ToolOnlineTestService testService) {
        this.service = service;
        this.testService = testService;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param toolType 业务类型
     * @param connectorId 资源标识
     * @param search {@code search}参数
     * @param includeInactive {@code includeInactive}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<ToolView>> list(
        @RequestParam(required = false) @Pattern(regexp = ALL_TYPES) String toolType,
        @RequestParam(required = false) @Positive Long connectorId,
        @RequestParam(required = false) @Size(max = 128) String search,
        @RequestParam(defaultValue = "false") boolean includeInactive,
        @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return R.ok(service.list(toolType, connectorId, search, includeInactive, limit));
    }

    /**
     * 处理{@code available}并返回对应结果。
     *
     * @param toolType 业务类型
     * @param connectorId 资源标识
     * @param search {@code search}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/available")
    public R<List<ToolView>> available(
        @RequestParam(required = false) @Pattern(regexp = ALL_TYPES) String toolType,
        @RequestParam(required = false) @Positive Long connectorId,
        @RequestParam(required = false) @Size(max = 128) String search,
        @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return R.ok(service.available(toolType, connectorId, search, limit));
    }

    /**
     * 处理{@code builtins}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/builtins")
    public R<List<Map<String, Object>>> builtins() {
        return R.ok(BuiltinToolCatalog.descriptors());
    }

    /**
     * 获取{@code get}。
     *
     * @param toolId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{toolId}")
    public R<ToolView> get(@PathVariable @Positive Long toolId) {
        return R.ok(service.get(toolId));
    }

    /**
     * 处理{@code versions}并返回对应结果。
     *
     * @param toolKey 工具Key参数
     * @return 处理结果
     */
    @GetMapping("/key/{toolKey}/versions")
    public R<List<ToolView>> versions(
        @PathVariable @Size(max = 128) String toolKey
    ) {
        return R.ok(service.versions(toolKey));
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping
    public R<ToolView> create(@Valid @RequestBody CreateToolRequest request) {
        return R.ok(service.create(request));
    }

    /**
     * 创建并保存版本。
     *
     * @param toolKey 工具Key参数
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/key/{toolKey}/versions")
    public R<ToolView> createVersion(
        @PathVariable @Size(max = 128) String toolKey,
        @Valid @RequestBody CreateToolVersionRequest request
    ) {
        return R.ok(service.createVersion(toolKey, request));
    }

    /**
     * 更新{@code Status}。
     *
     * @param toolId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PatchMapping("/{toolId}/status")
    public R<ToolView> updateStatus(
        @PathVariable @Positive Long toolId,
        @Valid @RequestBody UpdateToolStatusRequest request
    ) {
        return R.ok(service.updateStatus(toolId, request));
    }

    /**
     * 处理{@code test}并返回对应结果。
     *
     * @param toolId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{toolId}/test")
    public R<ToolOnlineTestView> test(
        @PathVariable @Positive Long toolId,
        @Valid @RequestBody ToolOnlineTestRequest request
    ) {
        return R.ok(testService.execute(toolId, request));
    }

    /**
     * 删除{@code delete}。
     *
     * @param toolId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/{toolId}")
    public R<Void> delete(@PathVariable @Positive Long toolId) {
        service.delete(toolId);
        return R.ok();
    }
}
