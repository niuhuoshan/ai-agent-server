package group.aitools.nhs.platform.data.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.data.service.DataProfileApplicationService;
import group.aitools.nhs.platform.data.service.DataSmartImportService;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.ApplySmartImportRequest;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.CreateProfileJobRequest;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.CreateSmartImportPreviewRequest;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.ProfileJobDetailView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.ProfileJobView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.RelationRecommendationView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.SmartImportApplyResultView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.SmartImportPreviewView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.TableProfileDetailView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.TableProfilePageView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.TableProfileStatsView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.UpdateProfileIgnoreRequest;
import group.aitools.nhs.common.core.domain.R;
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

/** Durable Metadata Profile jobs, profile facts and selective smart-import operations. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/datasets/{datasetId}")
public class PlatformDataProfileController {

    private final DataProfileApplicationService profileService;
    private final DataSmartImportService importService;

    public PlatformDataProfileController(
        DataProfileApplicationService profileService,
        DataSmartImportService importService
    ) {
        this.profileService = profileService;
        this.importService = importService;
    }

    @PostMapping("/profile-jobs")
    public R<ProfileJobView> createJob(
        @PathVariable @Positive Long datasetId,
        @Valid @RequestBody CreateProfileJobRequest request
    ) {
        return R.ok(profileService.createJob(datasetId, request));
    }

    @GetMapping("/profile-jobs")
    public R<List<ProfileJobView>> jobs(
        @PathVariable @Positive Long datasetId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        return R.ok(profileService.jobs(datasetId, limit));
    }

    @GetMapping("/profile-jobs/{jobId}")
    public R<ProfileJobDetailView> job(
        @PathVariable @Positive Long datasetId,
        @PathVariable @Positive Long jobId
    ) {
        return R.ok(profileService.job(datasetId, jobId));
    }

    @PostMapping("/profile-jobs/{jobId}/cancel")
    public R<ProfileJobView> cancelJob(
        @PathVariable @Positive Long datasetId,
        @PathVariable @Positive Long jobId
    ) {
        return R.ok(profileService.cancelJob(datasetId, jobId));
    }

    @PostMapping("/profile-jobs/{jobId}/resume")
    public R<ProfileJobView> resumeJob(
        @PathVariable @Positive Long datasetId,
        @PathVariable @Positive Long jobId
    ) {
        return R.ok(profileService.resumeJob(datasetId, jobId));
    }

    @GetMapping("/table-profiles")
    public R<TableProfilePageView> profiles(
        @PathVariable @Positive Long datasetId,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize,
        @RequestParam(required = false) String query,
        @RequestParam(required = false) String tag,
        @RequestParam(required = false) Boolean ignored,
        @RequestParam(required = false)
        @Pattern(regexp = "business|temporary|backup|staging|system") String classification,
        @RequestParam(required = false)
        @Pattern(regexp = "pending|running|success|failed") String status,
        @RequestParam(defaultValue = "default")
        @Pattern(regexp = "default|confidence|confidence_score|name|table_name|term|created|created_at")
        String sortBy,
        @RequestParam(defaultValue = "desc") @Pattern(regexp = "asc|desc") String sortOrder
    ) {
        return R.ok(profileService.profiles(
            datasetId, page, pageSize, query, tag, ignored, classification,
            status, sortBy, sortOrder
        ));
    }

    @GetMapping("/table-profiles/stats")
    public R<TableProfileStatsView> stats(@PathVariable @Positive Long datasetId) {
        return R.ok(profileService.stats(datasetId));
    }

    @GetMapping("/table-profiles/{tableId}")
    public R<TableProfileDetailView> profile(
        @PathVariable @Positive Long datasetId,
        @PathVariable @Positive Long tableId,
        @RequestParam(defaultValue = "15") @Min(1) @Max(30) int relatedLimit
    ) {
        return R.ok(profileService.profile(datasetId, tableId, relatedLimit));
    }

    @PutMapping("/table-profiles/{tableId}/ignore")
    public R<TableProfileDetailView> updateIgnore(
        @PathVariable @Positive Long datasetId,
        @PathVariable @Positive Long tableId,
        @Valid @RequestBody UpdateProfileIgnoreRequest request
    ) {
        return R.ok(profileService.updateIgnore(datasetId, tableId, request));
    }

    @GetMapping("/table-profiles/{tableId}/related")
    public R<List<RelationRecommendationView>> related(
        @PathVariable @Positive Long datasetId,
        @PathVariable @Positive Long tableId,
        @RequestParam(defaultValue = "15") @Min(1) @Max(30) int limit
    ) {
        return R.ok(profileService.related(datasetId, tableId, limit));
    }

    @PostMapping("/smart-import/previews")
    public R<SmartImportPreviewView> createPreview(
        @PathVariable @Positive Long datasetId,
        @Valid @RequestBody CreateSmartImportPreviewRequest request
    ) {
        return R.ok(importService.createPreview(datasetId, request));
    }

    @GetMapping("/smart-import/previews/{previewId}")
    public R<SmartImportPreviewView> preview(
        @PathVariable @Positive Long datasetId,
        @PathVariable @Positive Long previewId
    ) {
        return R.ok(importService.preview(datasetId, previewId));
    }

    @PostMapping("/smart-import/previews/{previewId}/apply")
    public R<SmartImportApplyResultView> apply(
        @PathVariable @Positive Long datasetId,
        @PathVariable @Positive Long previewId,
        @Valid @RequestBody ApplySmartImportRequest request
    ) {
        return R.ok(importService.apply(datasetId, previewId, request));
    }
}
