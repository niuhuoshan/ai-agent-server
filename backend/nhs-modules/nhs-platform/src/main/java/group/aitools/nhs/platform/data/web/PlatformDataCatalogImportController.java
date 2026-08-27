package group.aitools.nhs.platform.data.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.data.service.DataCatalogImportService;
import group.aitools.nhs.platform.data.service.DataCatalogImportService.ExportedMetadataYaml;
import group.aitools.nhs.platform.data.web.MetadataCatalogImportContracts.ApplyMetadataImportPreviewRequest;
import group.aitools.nhs.platform.data.web.MetadataCatalogImportContracts.CreateMetadataImportPreviewRequest;
import group.aitools.nhs.platform.data.web.MetadataCatalogImportContracts.MetadataImportApplyView;
import group.aitools.nhs.platform.data.web.MetadataCatalogImportContracts.MetadataImportPreviewView;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/** Canonical metadata YAML export and durable DDL/YAML import endpoints. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/datasets/{datasetId}")
public class PlatformDataCatalogImportController {

    private static final MediaType YAML = new MediaType("application", "yaml", StandardCharsets.UTF_8);

    private final DataCatalogImportService service;

    public PlatformDataCatalogImportController(DataCatalogImportService service) {
        this.service = service;
    }

    @GetMapping("/metadata.yaml")
    public ResponseEntity<byte[]> exportYaml(@PathVariable @Positive Long datasetId) {
        ExportedMetadataYaml export = service.exportYaml(datasetId);
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(export.fileName(), StandardCharsets.UTF_8)
            .build();
        byte[] content = export.content();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .contentType(YAML)
            .contentLength(content.length)
            .body(content);
    }

    @PostMapping("/metadata-import/previews")
    public R<MetadataImportPreviewView> createPreview(
        @PathVariable @Positive Long datasetId,
        @Valid @RequestBody CreateMetadataImportPreviewRequest request
    ) {
        return R.ok(service.createPreview(datasetId, request));
    }

    @GetMapping("/metadata-import/previews/{previewId}")
    public R<MetadataImportPreviewView> preview(
        @PathVariable @Positive Long datasetId,
        @PathVariable @Positive Long previewId
    ) {
        return R.ok(service.preview(datasetId, previewId));
    }

    @PostMapping("/metadata-import/previews/{previewId}/apply")
    public R<MetadataImportApplyView> apply(
        @PathVariable @Positive Long datasetId,
        @PathVariable @Positive Long previewId,
        @Valid @RequestBody ApplyMetadataImportPreviewRequest request
    ) {
        return R.ok(service.apply(datasetId, previewId, request));
    }
}
