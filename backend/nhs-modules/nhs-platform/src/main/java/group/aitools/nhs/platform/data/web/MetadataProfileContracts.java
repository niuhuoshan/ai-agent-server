package group.aitools.nhs.platform.data.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** HTTP contracts for durable metadata profiling and selective smart import. */
public final class MetadataProfileContracts {

    private MetadataProfileContracts() {
    }

    public record CreateProfileJobRequest(
        @NotBlank @Pattern(regexp = "full|incremental") String mode,
        @Size(max = 5000) List<@NotNull @Positive Long> tableIds
    ) {
        public CreateProfileJobRequest {
            tableIds = tableIds == null ? List.of() : List.copyOf(tableIds);
        }

        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的画像任务字段：" + field);
        }
    }

    /** Job status uses the Nhs-compatible queued/running/done/error/cancelled vocabulary. */
    public record ProfileJobView(
        Long id,
        Long datasetId,
        Long dataSourceId,
        String mode,
        String status,
        Integer totalTables,
        Integer completedTables,
        Integer failedTables,
        BigDecimal progressPercent,
        Long currentTableId,
        boolean cancelRequested,
        Long resumeOfJobId,
        Integer attemptNo,
        Integer maxAttempts,
        Integer revisionNo,
        String errorMessage,
        Long requestedBy,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime updatedAt
    ) {
    }

    /** Table status uses pending/running/success/failed for source compatibility. */
    public record ProfileJobTableView(
        Long id,
        Long jobId,
        Long tableId,
        String schemaName,
        String tableName,
        String status,
        Integer sequenceNo,
        Integer attemptNo,
        Long profileId,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime updatedAt
    ) {
    }

    public record ProfileJobDetailView(
        ProfileJobView job,
        List<ProfileJobTableView> tables
    ) {
        public ProfileJobDetailView {
            tables = tables == null ? List.of() : List.copyOf(tables);
        }
    }

    public record TableProfileSummaryView(
        Long profileId,
        Long datasetId,
        Long tableId,
        Long jobId,
        String schemaName,
        String tableName,
        String displayName,
        String term,
        String description,
        String tableType,
        String status,
        Integer columnCount,
        Integer sampleRowCount,
        BigDecimal confidenceScore,
        String confidenceReason,
        List<String> tags,
        String temporaryClassification,
        Boolean ignored,
        String ignoreDecision,
        Integer revisionNo,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
        public TableProfileSummaryView {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    public record TableProfilePageView(
        List<TableProfileSummaryView> items,
        long total,
        int page,
        int pageSize,
        int pages
    ) {
        public TableProfilePageView {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record ColumnProfileView(
        Long columnId,
        String physicalName,
        String displayName,
        String term,
        String description,
        String dataType,
        boolean primary,
        boolean sensitive,
        int nonNullSampleCount,
        int distinctSampleCount,
        List<String> examples
    ) {
        public ColumnProfileView {
            examples = examples == null ? List.of() : List.copyOf(examples);
        }
    }

    public record SampleValueView(
        Long columnId,
        String columnName,
        String displayName,
        String value,
        String valueType,
        boolean redacted,
        boolean truncated
    ) {
    }

    public record SampleRowView(
        int rowNo,
        List<SampleValueView> values
    ) {
        public SampleRowView {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record RelationRecommendationView(
        Long id,
        Long datasetId,
        Long profileJobId,
        Long sourceTableId,
        String sourceTableName,
        Long sourceColumnId,
        String sourceColumnName,
        Long targetTableId,
        String targetTableName,
        Long targetColumnId,
        String targetColumnName,
        BigDecimal confidenceScore,
        String joinType,
        String joinCondition,
        String reason,
        String status
    ) {
    }

    public record TableProfileDetailView(
        TableProfileSummaryView summary,
        String ddl,
        Long rowCountEstimate,
        boolean sampleRedacted,
        List<ColumnProfileView> columns,
        List<SampleRowView> samples,
        List<RelationRecommendationView> related
    ) {
        public TableProfileDetailView {
            columns = columns == null ? List.of() : List.copyOf(columns);
            samples = samples == null ? List.of() : List.copyOf(samples);
            related = related == null ? List.of() : List.copyOf(related);
        }
    }

    public record ProfileTagStatView(String name, long count) {
    }

    public record TableProfileStatsView(
        long totalProfiles,
        long tableCount,
        long viewCount,
        long ignoredCount,
        long temporaryCount,
        BigDecimal averageConfidence,
        LocalDateTime lastProfiledAt,
        List<ProfileTagStatView> tags
    ) {
        public TableProfileStatsView {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    public record UpdateProfileIgnoreRequest(
        @NotNull @Positive Integer revisionNo,
        boolean ignored
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignoredValue) {
            throw new IllegalArgumentException("不支持的画像忽略字段：" + field);
        }
    }

    public record CreateSmartImportPreviewRequest(
        @Positive Long profileJobId,
        @NotEmpty @Size(max = 5000) List<@NotNull @Positive Long> tableIds
    ) {
        public CreateSmartImportPreviewRequest {
            tableIds = tableIds == null ? List.of() : List.copyOf(tableIds);
        }

        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的智能导入预览字段：" + field);
        }
    }

    public record TableGovernanceSnapshotView(
        String displayName,
        String description,
        String status,
        boolean metadataPresent,
        String stateHash
    ) {
    }

    public record ColumnGovernanceSnapshotView(
        Long columnId,
        String displayName,
        String description,
        boolean sensitive,
        String status,
        boolean metadataPresent,
        String stateHash
    ) {
    }

    public record ColumnImportProposalView(
        Long columnId,
        ColumnGovernanceSnapshotView expected,
        String displayName,
        String description,
        boolean sensitive,
        String status
    ) {
    }

    public record TableImportProposalView(
        Long profileId,
        Integer profileRevision,
        Long tableId,
        String sourceHash,
        String schemaName,
        String physicalName,
        TableGovernanceSnapshotView expected,
        String displayName,
        String description,
        String status,
        List<ColumnImportProposalView> columnUpdates
    ) {
        public TableImportProposalView {
            columnUpdates = columnUpdates == null ? List.of() : List.copyOf(columnUpdates);
        }
    }

    public record RelationshipImportProposalView(
        Long recommendationId,
        Long sourceTableId,
        Long sourceColumnId,
        Long targetTableId,
        Long targetColumnId,
        Long sourceProfileId,
        Integer sourceProfileRevision,
        String sourceStructureHash,
        Long targetProfileId,
        Integer targetProfileRevision,
        String targetStructureHash,
        String sourceTableStateHash,
        String sourceColumnStateHash,
        String targetTableStateHash,
        String targetColumnStateHash,
        String joinType,
        String joinCondition,
        String description
    ) {
    }

    public record SmartImportItemView(
        Long id,
        String itemType,
        Long resourceId,
        String status,
        String contentHash,
        TableImportProposalView tableProposal,
        RelationshipImportProposalView relationshipProposal,
        Long appliedResourceId,
        String errorMessage
    ) {
    }

    public record SmartImportPreviewView(
        Long id,
        Long datasetId,
        Long profileJobId,
        String status,
        Integer datasetRevision,
        Integer revisionNo,
        LocalDateTime expiresAt,
        Long createdBy,
        LocalDateTime createdAt,
        Long appliedBy,
        LocalDateTime appliedAt,
        List<SmartImportItemView> items
    ) {
        public SmartImportPreviewView {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record ApplySmartImportRequest(
        @NotNull @Positive Integer revisionNo,
        @NotEmpty @Size(max = 10000) List<@NotNull @Positive Long> itemIds
    ) {
        public ApplySmartImportRequest {
            itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
        }

        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的智能导入应用字段：" + field);
        }
    }

    public record SmartImportAppliedItemView(
        Long itemId,
        String itemType,
        Long sourceResourceId,
        Long appliedResourceId
    ) {
    }

    public record SmartImportApplyResultView(
        SmartImportPreviewView preview,
        List<SmartImportAppliedItemView> appliedItems
    ) {
        public SmartImportApplyResultView {
            appliedItems = appliedItems == null ? List.of() : List.copyOf(appliedItems);
        }
    }
}
