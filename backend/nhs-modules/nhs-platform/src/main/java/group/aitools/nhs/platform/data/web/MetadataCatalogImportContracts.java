package group.aitools.nhs.platform.data.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP contracts for canonical metadata export and durable DDL/YAML import. */
public final class MetadataCatalogImportContracts {

    private MetadataCatalogImportContracts() {
    }

    public record CreateMetadataImportPreviewRequest(
        @NotBlank @Pattern(regexp = "ddl|yaml") String format,
        @NotBlank @Size(max = 2_000_000) String content
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的元数据导入字段：" + field);
        }
    }

    public record MetadataImportDiagnosticView(
        String level,
        String code,
        String message,
        String resourceKey
    ) {
    }

    public record MetadataImportItemView(
        Long id,
        String itemType,
        String resourceKey,
        String action,
        String status,
        String currentHash,
        String contentHash,
        Map<String, Object> proposal,
        Long appliedResourceId,
        String errorMessage
    ) {
        public MetadataImportItemView {
            proposal = proposal == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(proposal));
        }
    }

    public record MetadataImportPreviewView(
        Long id,
        Long datasetId,
        String sourceType,
        String status,
        Integer datasetRevision,
        Integer revisionNo,
        Integer tableCount,
        Integer columnCount,
        List<MetadataImportDiagnosticView> diagnostics,
        LocalDateTime expiresAt,
        Long createdBy,
        LocalDateTime createdAt,
        Long appliedBy,
        LocalDateTime appliedAt,
        List<MetadataImportItemView> items
    ) {
        public MetadataImportPreviewView {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record ApplyMetadataImportPreviewRequest(
        @NotNull @Positive Integer revisionNo,
        @NotEmpty @Size(max = 10_000) List<@NotNull @Positive Long> itemIds
    ) {
        public ApplyMetadataImportPreviewRequest {
            itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
        }

        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的元数据应用字段：" + field);
        }
    }

    public record MetadataImportApplyView(
        Long previewId,
        String status,
        Integer datasetRevision,
        Integer revisionNo,
        List<Long> appliedItemIds,
        List<Long> skippedItemIds,
        LocalDateTime appliedAt
    ) {
        public MetadataImportApplyView {
            appliedItemIds = appliedItemIds == null ? List.of() : List.copyOf(appliedItemIds);
            skippedItemIds = skippedItemIds == null ? List.of() : List.copyOf(skippedItemIds);
        }
    }
}
