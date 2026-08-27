package group.aitools.nhs.platform.data.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.data.service.DataMetadataSyncService;
import group.aitools.nhs.platform.data.service.DataQueryExecutionService;
import group.aitools.nhs.platform.data.service.DataQueryExportService;
import group.aitools.nhs.platform.data.service.DataQueryExportService.ExportedCsv;
import group.aitools.nhs.platform.data.service.DataQueryExportService.ExportedFile;
import group.aitools.nhs.platform.data.service.DataSourceCatalogService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import java.nio.charset.StandardCharsets;

/** Data source, metadata and read-only ChatBI control plane. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform")
public class PlatformDataController {

    private final DataSourceCatalogService catalogService;
    private final DataMetadataSyncService metadataSyncService;
    private final DataQueryExecutionService queryExecutionService;
    private final DataQueryExportService queryExportService;

    public PlatformDataController(
        DataSourceCatalogService catalogService,
        DataMetadataSyncService metadataSyncService,
        DataQueryExecutionService queryExecutionService,
        DataQueryExportService queryExportService
    ) {
        this.catalogService = catalogService;
        this.metadataSyncService = metadataSyncService;
        this.queryExecutionService = queryExecutionService;
        this.queryExportService = queryExportService;
    }

    @GetMapping("/data-sources")
    public R<List<DataSourceView>> listSources(
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(catalogService.listSources(limit));
    }

    @GetMapping("/data-sources/{sourceId}")
    public R<DataSourceView> getSource(@PathVariable @Positive Long sourceId) {
        return R.ok(catalogService.getSource(sourceId));
    }

    @PostMapping("/data-sources")
    public R<DataSourceView> createSource(@Valid @RequestBody CreateDataSourceRequest request) {
        return R.ok(catalogService.createSource(request));
    }

    @PutMapping("/data-sources/{sourceId}")
    public R<DataSourceView> updateSource(
        @PathVariable @Positive Long sourceId,
        @Valid @RequestBody UpdateDataSourceRequest request
    ) {
        return R.ok(catalogService.updateSource(sourceId, request));
    }

    @DeleteMapping("/data-sources/{sourceId}")
    public R<Void> deleteSource(@PathVariable @Positive Long sourceId) {
        catalogService.deleteSource(sourceId);
        return R.ok();
    }

    @PostMapping("/data-sources/{sourceId}/test")
    public R<DataSourceConnectionView> testSource(@PathVariable @Positive Long sourceId) {
        return R.ok(catalogService.testConnection(sourceId));
    }

    @GetMapping("/datasets")
    public R<List<DatasetView>> listDatasets(
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(catalogService.listDatasets(limit));
    }

    @GetMapping("/datasets/{datasetId}")
    public R<DatasetView> getDataset(@PathVariable @Positive Long datasetId) {
        return R.ok(catalogService.getDataset(datasetId));
    }

    @PostMapping("/datasets")
    public R<DatasetView> createDataset(@Valid @RequestBody CreateDatasetRequest request) {
        return R.ok(catalogService.createDataset(request));
    }

    @PutMapping("/datasets/{datasetId}")
    public R<DatasetView> updateDataset(
        @PathVariable @Positive Long datasetId,
        @Valid @RequestBody UpdateDatasetRequest request
    ) {
        return R.ok(catalogService.updateDataset(datasetId, request));
    }

    @DeleteMapping("/datasets/{datasetId}")
    public R<Void> deleteDataset(@PathVariable @Positive Long datasetId) {
        catalogService.deleteDataset(datasetId);
        return R.ok();
    }

    @GetMapping("/datasets/{datasetId}/delete-impact")
    public R<DatasetDeleteImpactView> datasetDeleteImpact(
        @PathVariable @Positive Long datasetId
    ) {
        return R.ok(catalogService.datasetDeleteImpact(datasetId));
    }

    @GetMapping("/datasets/{datasetId}/metadata")
    public R<List<DataTableView>> metadata(@PathVariable @Positive Long datasetId) {
        return R.ok(catalogService.metadata(datasetId));
    }

    @PostMapping("/datasets/{datasetId}/metadata/sync")
    public R<MetadataSyncView> synchronize(@PathVariable @Positive Long datasetId) {
        return R.ok(metadataSyncService.synchronize(datasetId));
    }

    @PutMapping("/datasets/{datasetId}/tables/{tableId}")
    public R<Void> updateTable(
        @PathVariable @Positive Long datasetId,
        @PathVariable @Positive Long tableId,
        @Valid @RequestBody UpdateDataTableRequest request
    ) {
        catalogService.updateTable(datasetId, tableId, request);
        return R.ok();
    }

    @PutMapping("/datasets/{datasetId}/columns/{columnId}")
    public R<Void> updateColumn(
        @PathVariable @Positive Long datasetId,
        @PathVariable @Positive Long columnId,
        @Valid @RequestBody UpdateDataColumnRequest request
    ) {
        catalogService.updateColumn(datasetId, columnId, request);
        return R.ok();
    }

    @PostMapping("/data-queries/validate")
    public R<DataQueryValidationView> validateQuery(@Valid @RequestBody DataQueryRequest request) {
        return R.ok(queryExecutionService.validate(request));
    }

    @PostMapping("/data-queries/execute")
    public R<DataQueryResultView> executeQuery(@Valid @RequestBody DataQueryRequest request) {
        return R.ok(queryExecutionService.execute(request));
    }

    @GetMapping("/data-queries/{queryId}/export.csv")
    public ResponseEntity<byte[]> exportQuery(@PathVariable @Positive Long queryId) {
        ExportedCsv export = queryExportService.export(queryId);
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(export.fileName(), StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .contentLength(export.content().length)
            .body(export.content());
    }

    @GetMapping("/data-queries/{queryId}/export")
    public ResponseEntity<byte[]> exportQuery(
        @PathVariable @Positive Long queryId,
        @RequestParam(defaultValue = "csv") String format
    ) {
        ExportedFile export = queryExportService.exportFile(queryId, format);
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(export.fileName(), StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .contentType(MediaType.parseMediaType(export.mediaType()))
            .contentLength(export.content().length)
            .body(export.content());
    }
}
