package group.aitools.nhs.platform.identity.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.identity.service.IdentitySyncApplicationService;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.ColumnOption;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.ConfigView;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.DataSourceOption;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.PreviewRequest;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.PreviewView;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.RunRequest;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.RunView;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.TableOption;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.UpdateConfigRequest;
import group.aitools.nhs.common.core.domain.R;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.validation.annotation.Validated;
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
 * 提供身份Sync相关的 HTTP 接口，并负责请求校验与结果返回。
 * Native and Nhs-compatible identity-provider synchronization endpoints. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping({"/platform/identity-sync", "/api/portal/management/third-party-sync"})
public class IdentitySyncController {

    private final IdentitySyncApplicationService service;

    public IdentitySyncController(IdentitySyncApplicationService service) {
        this.service = service;
    }

    /**
     * 处理{@code config}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/config")
    public R<ConfigView> config() {
        return R.ok(service.config());
    }

    /**
     * 更新{@code Config}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/config")
    public R<ConfigView> updateConfig(@Valid @RequestBody UpdateConfigRequest request) {
        return R.ok(service.updateConfig(request));
    }

    /**
     * 处理数据Sources并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/datasources")
    public R<List<DataSourceOption>> dataSources() {
        return R.ok(service.dataSources());
    }

    /**
     * 处理{@code tables}并返回对应结果。
     *
     * @param dataSourceId 资源标识
     * @param legacyDataSourceId 资源标识
     * @return 处理结果
     */
    @GetMapping("/tables")
    public R<List<TableOption>> tables(
        @RequestParam(required = false) @Positive Long dataSourceId,
        @RequestParam(name = "connection_config_id", required = false) @Positive Long legacyDataSourceId
    ) {
        return R.ok(service.tables(requiredId(dataSourceId, legacyDataSourceId)));
    }

    /**
     * 处理{@code columns}并返回对应结果。
     *
     * @param dataSourceId 资源标识
     * @param legacyDataSourceId 资源标识
     * @param tableName 名称
     * @param legacyTableName 名称
     * @return 处理结果
     */
    @GetMapping("/columns")
    public R<List<ColumnOption>> columns(
        @RequestParam(required = false) @Positive Long dataSourceId,
        @RequestParam(name = "connection_config_id", required = false) @Positive Long legacyDataSourceId,
        @RequestParam(required = false) String tableName,
        @RequestParam(name = "table_name", required = false) String legacyTableName
    ) {
        return R.ok(service.columns(
            requiredId(dataSourceId, legacyDataSourceId), requiredTable(tableName, legacyTableName)
        ));
    }

    /**
     * 处理{@code previewSaved}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/preview")
    public R<PreviewView> previewSaved() {
        return R.ok(service.preview(new PreviewRequest(null)));
    }

    /**
     * 处理{@code preview}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/preview")
    public R<PreviewView> preview(@Valid @RequestBody(required = false) PreviewRequest request) {
        return R.ok(service.preview(request == null ? new PreviewRequest(null) : request));
    }

    /**
     * 执行{@code execute}相关的处理流程。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/run")
    public R<RunView> execute(@Valid @RequestBody(required = false) RunRequest request) {
        return R.ok(service.execute(request == null ? new RunRequest(List.of(), null) : request));
    }

    /**
     * 执行{@code s}相关的处理流程。
     *
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/runs")
    public R<List<RunView>> runs(
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        return R.ok(service.runs(limit));
    }

    /**
     * 执行{@code run}相关的处理流程。
     *
     * @param runId 资源标识
     * @return 处理结果
     */
    @GetMapping("/runs/{runId}")
    public R<RunView> run(@PathVariable @Positive Long runId) {
        return R.ok(service.run(runId));
    }

    /**
     * 处理{@code retry}并返回对应结果。
     *
     * @param runId 资源标识
     * @return 处理结果
     */
    @PostMapping("/runs/{runId}/retry")
    public R<RunView> retry(@PathVariable @Positive Long runId) {
        return R.ok(service.retry(runId));
    }

    /**
     * 校验{@code dId}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param legacyValue {@code legacyValue}参数
     * @return 处理结果
     */
    private Long requiredId(Long value, Long legacyValue) {
        Long selected = value == null ? legacyValue : value;
        if (selected == null) {
            throw new ServiceException("dataSourceId不能为空", 400);
        }
        return selected;
    }

    /**
     * 校验{@code dTable}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param legacyValue {@code legacyValue}参数
     * @return 处理结果
     */
    private String requiredTable(String value, String legacyValue) {
        String selected = value == null || value.isBlank() ? legacyValue : value;
        if (selected == null || selected.isBlank()) {
            throw new ServiceException("tableName不能为空", 400);
        }
        return selected;
    }
}
