package group.aitools.nhs.platform.audit.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.audit.service.MetadataChangelogApplicationService;
import group.aitools.nhs.platform.data.service.DataGovernanceService;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.MetadataChangeView;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提供元数据Changelog相关的 HTTP 接口，并负责请求校验与结果返回。
 * Cross-resource changelog plus Nhs-compatible dataset history paths. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping({"/platform/metadata-changelog", "/api/portal/changelog"})
public class MetadataChangelogController {

    private final MetadataChangelogApplicationService service;
    private final DataGovernanceService governanceService;

    public MetadataChangelogController(
        MetadataChangelogApplicationService service,
        DataGovernanceService governanceService
    ) {
        this.service = service;
        this.governanceService = governanceService;
    }

    /**
     * 查询{@code search}列表。
     *
     * @param datasetId 资源标识
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param action {@code action}参数
     * @param actorId 资源标识
     * @param createdFrom {@code createdFrom}参数
     * @param createdTo {@code createdTo}参数
     * @param page {@code page}参数
     * @param size 数量上限
     * @return 处理结果
     */
    @GetMapping({"", "/"})
    public R<MetadataChangelogPageView> search(
        @RequestParam(required = false) @Positive Long datasetId,
        @RequestParam(required = false) String resourceType,
        @RequestParam(required = false) @Positive Long resourceId,
        @RequestParam(required = false) String action,
        @RequestParam(required = false) @Positive Long actorId,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdFrom,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdTo,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size
    ) {
        return R.ok(service.search(
            datasetId, resourceType, resourceId, action, actorId, createdFrom, createdTo, page, size
        ));
    }

    /**
     * 处理数据集并返回对应结果。
     *
     * @param datasetId 资源标识
     * @param limit 数量上限
     * @param offset 起始位置或序号
     * @return 处理结果
     */
    @GetMapping("/datasets/{datasetId}")
    public R<List<MetadataChangeView>> dataset(
        @PathVariable @Positive Long datasetId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
        @RequestParam(defaultValue = "0") @Min(0) int offset
    ) {
        if (offset >= 500) return R.ok(List.of());
        int readLimit = Math.min(500, offset + limit);
        List<MetadataChangeView> rows = governanceService.changes(datasetId, readLimit);
        return R.ok(offset >= rows.size()
            ? List.of() : List.copyOf(rows.subList(offset, Math.min(rows.size(), offset + limit))));
    }

    /**
     * 处理统计并返回对应结果。
     *
     * @param days {@code days}参数
     * @return 处理结果
     */
    @GetMapping("/stats")
    public R<MetadataChangelogStatsView> statistics(
        @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days
    ) {
        return R.ok(service.statistics(days));
    }

    /**
     * 处理{@code diff}并返回对应结果。
     *
     * @param changeId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{changeId}/diff")
    public R<MetadataChangeDiffView> diff(@PathVariable @Positive Long changeId) {
        return R.ok(service.diff(changeId));
    }

    /**
     * 处理资源并返回对应结果。
     *
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param page {@code page}参数
     * @param size 数量上限
     * @return 处理结果
     */
    @GetMapping("/{resourceType}/{resourceId}")
    public R<MetadataChangelogPageView> resource(
        @PathVariable String resourceType,
        @PathVariable @Positive Long resourceId,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size
    ) {
        return R.ok(service.resource(resourceType, resourceId, page, size));
    }
}
