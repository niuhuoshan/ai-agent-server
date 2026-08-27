package group.aitools.nhs.platform.nhs.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 表示数据集NavigationContracts相关的领域对象。
 * Typed Nhs V1 dataset-navigation request and response contracts. */
public final class DatasetNavigationContracts {

    private DatasetNavigationContracts() {
    }

    /**
     * 封装{@code Click}相关的不可变数据。
     */
    public record ClickRequest(
        @NotBlank @Size(max = 2000) String query,
        @Size(max = 200) String label,
        @JsonProperty("group_id") @Size(max = 128) String groupId,
        @JsonProperty("dataset_menu_hash") @Size(max = 64) String datasetMenuHash
    ) {
    }

    /**
     * 封装{@code Refresh}相关的不可变数据。
     */
    public record RefreshRequest(
        @JsonProperty("group_title") @NotBlank @Size(max = 200) String groupTitle,
        @NotEmpty @Size(max = 32) List<@NotBlank @Size(max = 255) String> tables,
        @JsonProperty("dataset_menu_hash") @Size(max = 64) String datasetMenuHash,
        @JsonProperty("group_id") @Size(max = 128) String groupId,
        @JsonProperty("exclude_questions") @Size(max = 100) List<Map<String, Object>> excludeQuestions,
        @Pattern(regexp = "questions|followups") String purpose
    ) {
        /**
         * 创建 {@code RefreshRequest} 实例并初始化所需依赖。
         *
         * @param groupTitle {@code groupTitle}参数
         * @param tables {@code tables}参数
         * @param datasetMenuHash 数据集MenuHash参数
         * @param groupId 资源标识
         * @param excludeQuestions {@code excludeQuestions}参数
         * @param purpose {@code purpose}参数
         */
        public RefreshRequest {
            tables = tables == null ? List.of() : List.copyOf(tables);
            excludeQuestions = excludeQuestions == null ? List.of() : List.copyOf(excludeQuestions);
            purpose = purpose == null || purpose.isBlank() ? "questions" : purpose;
        }
    }

    /**
     * 封装{@code ColumnInfo}相关的不可变数据。
     */
    public record ColumnInfo(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 255) String term,
        @Size(max = 128) String type,
        @Size(max = 1000) String description
    ) {
    }

    /**
     * 封装{@code TableRecommend}相关的不可变数据。
     */
    public record TableRecommendRequest(
        @NotBlank @Size(max = 255) String table,
        @JsonProperty("physical_table_name") @Size(max = 255) String physicalTableName,
        @JsonProperty("dataset_name") @Size(max = 255) String datasetName,
        @Size(max = 500) List<@Valid ColumnInfo> columns
    ) {
        /**
         * 创建 {@code TableRecommendRequest} 实例并初始化所需依赖。
         *
         * @param table {@code table}参数
         * @param physicalTableName 名称
         * @param datasetName 名称
         * @param columns {@code columns}参数
         */
        public TableRecommendRequest {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }

    /**
     * 封装追问相关的不可变数据。
     */
    public record Question(String label, String query, String type) {
        /**
         * 创建 {@code Question} 实例并初始化所需依赖。
         *
         * @param label {@code label}参数
         * @param query 查询参数
         */
        public Question(String label, String query) {
            this(label, query, "dynamic");
        }
    }

    /**
     * 封装{@code Refresh}相关的不可变数据。
     */
    public record RefreshResponse(
        List<Question> questions,
        @JsonProperty("refresh_disabled_reason") String refreshDisabledReason
    ) {
        /**
         * 创建 {@code RefreshResponse} 实例并初始化所需依赖。
         *
         * @param questions {@code questions}参数
         * @param refreshDisabledReason {@code refreshDisabledReason}参数
         */
        public RefreshResponse {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }

    /**
     * 封装{@code Navigation}相关的不可变数据。
     */
    public record NavigationResponse(
        @JsonProperty("dataset_count") int datasetCount,
        @JsonProperty("dataset_menu_hash") String datasetMenuHash,
        @JsonProperty("generated_at") String generatedAt,
        List<Map<String, Object>> groups,
        String markdown,
        @JsonProperty("is_fallback") boolean isFallback,
        @JsonProperty("has_datasets") boolean hasDatasets,
        @JsonProperty("from_cache") boolean fromCache,
        @JsonProperty("llm_generation_failed") boolean llmGenerationFailed,
        @JsonProperty("llm_error_message") String llmErrorMessage
    ) {
        /**
         * 创建 {@code NavigationResponse} 实例并初始化所需依赖。
         *
         * @param datasetCount 数据集Count参数
         * @param datasetMenuHash 数据集MenuHash参数
         * @param generatedAt {@code generatedAt}参数
         * @param groups {@code groups}参数
         * @param markdown {@code markdown}参数
         * @param isFallback {@code isFallback}参数
         * @param hasDatasets {@code hasDatasets}参数
         * @param fromCache from缓存参数
         * @param llmGenerationFailed {@code llmGenerationFailed}参数
         * @param llmErrorMessage 待处理内容
         */
        public NavigationResponse {
            groups = groups == null ? List.of() : List.copyOf(groups);
        }
    }
}
