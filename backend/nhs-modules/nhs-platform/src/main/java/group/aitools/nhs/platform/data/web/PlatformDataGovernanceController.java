package group.aitools.nhs.platform.data.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.data.service.DataGovernanceService;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.CreateMetricRequest;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.CreateRelationshipRequest;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.MetadataChangeView;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.MetricView;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.RelationshipView;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.RowPolicyView;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.UpdateMetricRequest;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.UpdateRelationshipRequest;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.UpdateRowPolicyRequest;
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

/** Dataset metric, relationship, row-policy and change-history operations. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/datasets/{datasetId}")
public class PlatformDataGovernanceController {

    private final DataGovernanceService service;

    public PlatformDataGovernanceController(DataGovernanceService service) {
        this.service = service;
    }

    @GetMapping("/metrics")
    public R<List<MetricView>> metrics(@PathVariable @Positive Long datasetId) {
        return R.ok(service.metrics(datasetId));
    }

    @PostMapping("/metrics")
    public R<MetricView> createMetric(
        @PathVariable @Positive Long datasetId,
        @Valid @RequestBody CreateMetricRequest request
    ) {
        return R.ok(service.createMetric(datasetId, request));
    }

    @PutMapping("/metrics/{metricId}")
    public R<MetricView> updateMetric(
        @PathVariable @Positive Long datasetId,
        @PathVariable @Positive Long metricId,
        @Valid @RequestBody UpdateMetricRequest request
    ) {
        return R.ok(service.updateMetric(datasetId, metricId, request));
    }

    @DeleteMapping("/metrics/{metricId}")
    public R<Void> archiveMetric(
        @PathVariable @Positive Long datasetId,
        @PathVariable @Positive Long metricId
    ) {
        service.archiveMetric(datasetId, metricId);
        return R.ok();
    }

    @GetMapping("/relationships")
    public R<List<RelationshipView>> relationships(@PathVariable @Positive Long datasetId) {
        return R.ok(service.relationships(datasetId));
    }

    @PostMapping("/relationships")
    public R<RelationshipView> createRelationship(
        @PathVariable @Positive Long datasetId,
        @Valid @RequestBody CreateRelationshipRequest request
    ) {
        return R.ok(service.createRelationship(datasetId, request));
    }

    @PutMapping("/relationships/{relationshipId}")
    public R<RelationshipView> updateRelationship(
        @PathVariable @Positive Long datasetId,
        @PathVariable @Positive Long relationshipId,
        @Valid @RequestBody UpdateRelationshipRequest request
    ) {
        return R.ok(service.updateRelationship(datasetId, relationshipId, request));
    }

    @DeleteMapping("/relationships/{relationshipId}")
    public R<Void> archiveRelationship(
        @PathVariable @Positive Long datasetId,
        @PathVariable @Positive Long relationshipId
    ) {
        service.archiveRelationship(datasetId, relationshipId);
        return R.ok();
    }

    @GetMapping("/row-policy")
    public R<RowPolicyView> rowPolicy(@PathVariable @Positive Long datasetId) {
        return R.ok(service.rowPolicy(datasetId));
    }

    @PutMapping("/row-policy")
    public R<RowPolicyView> updateRowPolicy(
        @PathVariable @Positive Long datasetId,
        @Valid @RequestBody UpdateRowPolicyRequest request
    ) {
        return R.ok(service.updateRowPolicy(datasetId, request));
    }

    @GetMapping("/metadata-changes")
    public R<List<MetadataChangeView>> changes(
        @PathVariable @Positive Long datasetId,
        @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return R.ok(service.changes(datasetId, limit));
    }
}
