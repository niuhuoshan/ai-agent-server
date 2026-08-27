package group.aitools.nhs.platform.data.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** HTTP contracts for versioned dataset metadata governance. */
public final class MetadataGovernanceContracts {

    private MetadataGovernanceContracts() {
    }

    public record MetricView(
        Long id,
        Long datasetId,
        String metricKey,
        String name,
        String description,
        String calculationLogic,
        String unit,
        String status,
        Integer versionNo,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
    }

    public record CreateMetricRequest(
        @NotBlank @Size(max = 128)
        @Pattern(regexp = "[a-z0-9][a-z0-9._-]*") String metricKey,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 4000) String description,
        @NotBlank @Size(max = 8000) String calculationLogic,
        @Size(max = 64) String unit,
        @NotBlank @Pattern(regexp = "active|inactive") String status
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的指标字段：" + field);
        }
    }

    public record UpdateMetricRequest(
        @NotNull @Positive Integer versionNo,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 4000) String description,
        @NotBlank @Size(max = 8000) String calculationLogic,
        @Size(max = 64) String unit,
        @NotBlank @Pattern(regexp = "active|inactive") String status
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的指标字段：" + field);
        }
    }

    public record RelationshipView(
        Long id,
        Long datasetId,
        Long sourceTableId,
        Long targetTableId,
        String sourceTableName,
        String targetTableName,
        String joinType,
        String joinCondition,
        String description,
        String status,
        Integer revisionNo,
        Long createdBy,
        LocalDateTime createdAt,
        Long updatedBy,
        LocalDateTime updatedAt
    ) {
    }

    public record CreateRelationshipRequest(
        @NotNull @Positive Long sourceTableId,
        @NotNull @Positive Long targetTableId,
        @NotBlank @Pattern(regexp = "inner|left|right|full") String joinType,
        @NotBlank @Size(max = 4000) String joinCondition,
        @Size(max = 4000) String description,
        @NotBlank @Pattern(regexp = "active|inactive") String status
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的关系字段：" + field);
        }
    }

    public record UpdateRelationshipRequest(
        @NotNull @Positive Integer revisionNo,
        @NotNull @Positive Long sourceTableId,
        @NotNull @Positive Long targetTableId,
        @NotBlank @Pattern(regexp = "inner|left|right|full") String joinType,
        @NotBlank @Size(max = 4000) String joinCondition,
        @Size(max = 4000) String description,
        @NotBlank @Pattern(regexp = "active|inactive") String status
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的关系字段：" + field);
        }
    }

    public record RowPolicyRule(
        @NotNull @Positive Long tableId,
        @NotNull @Positive Long columnId,
        @NotBlank @Pattern(regexp = "eq|ne") String operator,
        @NotBlank @Pattern(regexp = "principal_id|principal_username") String valueSource
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的行策略字段：" + field);
        }
    }

    public record RowPolicyView(
        Long datasetId,
        Integer revisionNo,
        boolean enabled,
        List<RowPolicyRule> rules,
        LocalDateTime updatedAt
    ) {
        public RowPolicyView {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    public record UpdateRowPolicyRequest(
        @NotNull @Positive Integer revisionNo,
        boolean enabled,
        @NotNull @Size(max = 64) List<@Valid RowPolicyRule> rules
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("不支持的行策略字段：" + field);
        }
    }

    public record MetadataChangeView(
        Long id,
        Long datasetId,
        String resourceType,
        Long resourceId,
        String action,
        String beforeJson,
        String afterJson,
        String beforeHash,
        String afterHash,
        Long actorId,
        LocalDateTime createdAt
    ) {
    }
}
