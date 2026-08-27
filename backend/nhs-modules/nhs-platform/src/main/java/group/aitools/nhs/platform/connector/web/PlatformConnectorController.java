package group.aitools.nhs.platform.connector.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import group.aitools.nhs.platform.connector.service.ConnectorCatalogService;
import group.aitools.nhs.platform.connector.service.McpRuntimeObservationService;
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
 * 提供平台连接器相关的 HTTP 接口，并负责请求校验与结果返回。
 * Administrative connector registry and MCP discovery endpoints. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping({"/platform/connectors", "/api/portal/connectors"})
public class PlatformConnectorController {

    private final ConnectorCatalogService service;
    private final McpRuntimeObservationService runtimeObservation;

    public PlatformConnectorController(
        ConnectorCatalogService service,
        McpRuntimeObservationService runtimeObservation
    ) {
        this.service = service;
        this.runtimeObservation = runtimeObservation;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param providerType 业务类型
     * @param search {@code search}参数
     * @param includeInactive {@code includeInactive}参数
     * @param scope 范围参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<ConnectorView>> list(
        @RequestParam(required = false) @Pattern(regexp = "api|mcp|search") String providerType,
        @RequestParam(required = false) @Size(max = 128) String search,
        @RequestParam(defaultValue = "false") boolean includeInactive,
        @RequestParam(required = false) @Pattern(regexp = "global|personal") String scope,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(service.list(providerType, search, includeInactive, scope, limit));
    }

    /**
     * 获取{@code get}。
     *
     * @param connectorId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{connectorId}")
    public R<ConnectorView> get(@PathVariable @Positive Long connectorId) {
        return R.ok(service.get(connectorId));
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping
    public R<ConnectorView> create(@Valid @RequestBody CreateConnectorRequest request) {
        return R.ok(service.create(request));
    }

    /**
     * 处理previewMcp导入并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/mcp/import/preview")
    public R<McpServersImportPreviewView> previewMcpImport(
        @Valid @RequestBody McpServersImportPreviewRequest request
    ) {
        return R.ok(service.previewMcpServers(request));
    }

    /**
     * 处理导入McpServer并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/mcp/import")
    public R<ConnectorView> importMcpServer(
        @Valid @RequestBody McpServersImportRequest request
    ) {
        return R.ok(service.importMcpServer(request));
    }

    /**
     * 处理{@code testDraft}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/mcp/test")
    public R<McpConnectionTestView> testDraft(
        @Valid @RequestBody McpConnectionTestRequest request
    ) {
        return R.ok(service.testDraftConnection(request));
    }

    /**
     * 校验{@code McpWizard}，并在条件不满足时终止处理。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/mcp/wizard/validate")
    public R<McpWizardValidationView> validateMcpWizard(
        @Valid @RequestBody McpWizardValidationRequest request
    ) {
        return R.ok(service.validateMcpWizard(request));
    }

    /**
     * 处理{@code publishMcpWizard}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{connectorId}/mcp/wizard/publish")
    public R<ConnectorView> publishMcpWizard(
        @PathVariable @Positive Long connectorId,
        @Valid @RequestBody McpWizardPublishRequest request
    ) {
        return R.ok(service.publishMcpWizard(connectorId, request));
    }

    /**
     * 更新{@code update}。
     *
     * @param connectorId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{connectorId}")
    public R<ConnectorView> update(
        @PathVariable @Positive Long connectorId,
        @Valid @RequestBody UpdateConnectorRequest request
    ) {
        return R.ok(service.update(connectorId, request));
    }

    /**
     * 删除{@code delete}。
     *
     * @param connectorId 资源标识
     * @param expectedRevision {@code expectedRevision}参数
     * @return 处理结果
     */
    @DeleteMapping("/{connectorId}")
    public R<Void> delete(
        @PathVariable @Positive Long connectorId,
        @RequestParam @Positive Long expectedRevision
    ) {
        service.delete(connectorId, expectedRevision);
        return R.ok();
    }

    /**
     * 处理{@code discover}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @return 处理结果
     */
    @PostMapping("/{connectorId}/discover")
    public R<McpDiscoveryView> discover(@PathVariable @Positive Long connectorId) {
        return R.ok(service.discover(connectorId));
    }

    /**
     * 处理{@code test}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @return 处理结果
     */
    @PostMapping("/{connectorId}/test")
    public R<McpConnectionTestView> test(@PathVariable @Positive Long connectorId) {
        return R.ok(service.testConnection(connectorId));
    }

    /**
     * 处理{@code discoveries}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/{connectorId}/discoveries")
    public R<List<McpDiscoveryView>> discoveries(
        @PathVariable @Positive Long connectorId,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return R.ok(service.discoveries(connectorId, limit));
    }

    /**
     * 执行{@code time}相关的处理流程。
     *
     * @param connectorId 资源标识
     * @param mountLimit 数量上限
     * @param usageLimit 数量上限
     * @return 处理结果
     */
    @GetMapping("/{connectorId}/runtime")
    public R<McpRuntimeOverviewView> runtime(
        @PathVariable @Positive Long connectorId,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int mountLimit,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int usageLimit
    ) {
        return R.ok(runtimeObservation.overview(connectorId, mountLimit, usageLimit));
    }

    /**
 * 处理{@code usage}并返回对应结果。
 * Nhs-compatible configured Agent binding usage for an MCP connector. */
    @GetMapping("/{connectorId}/usage")
    public R<McpConnectorUsageView> usage(@PathVariable @Positive Long connectorId) {
        return R.ok(runtimeObservation.usage(connectorId));
    }
}
