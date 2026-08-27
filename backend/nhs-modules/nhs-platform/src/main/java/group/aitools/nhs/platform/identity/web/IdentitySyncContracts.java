package group.aitools.nhs.platform.identity.web;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 表示身份SyncContracts相关的领域对象。
 * HTTP contracts for provider-driven local user synchronization. */
public final class IdentitySyncContracts {

    private IdentitySyncContracts() {
    }

    /**
     * 封装{@code Config}相关的不可变数据。
     */
    public record ConfigView(
        boolean enabled,
        String providerType,
        Long dataSourceId,
        String endpointUrl,
        String credentialRef,
        String authType,
        String credentialHeader,
        String requestMethod,
        Map<String, String> requestHeaders,
        Map<String, Object> requestBody,
        String responseItemsPath,
        String tableName,
        FieldMapping fieldMapping,
        List<ExtraMapping> extraMappings,
        String defaultRoleKey,
        String schedule,
        long revisionNo,
        LocalDateTime lastPreviewAt,
        LocalDateTime lastRunAt,
        String lastRunStatus,
        String lastError,
        LocalDateTime updateTime
    ) {
    }

    /**
     * 封装{@code FieldMapping}相关的不可变数据。
     */
    public record FieldMapping(
        @JsonAlias("user_name") @NotBlank @Size(max = 128) String userName,
        @JsonAlias("real_name") @Size(max = 128) String displayName,
        @Size(max = 128) String email,
        @JsonAlias("phone_number") @Size(max = 128) String phoneNumber,
        @Size(max = 128) String remark,
        @Size(max = 128) String status
    ) {
    }

    /**
     * 封装{@code ExtraMapping}相关的不可变数据。
     */
    public record ExtraMapping(
        @JsonAlias("json_key") @NotBlank @Size(max = 128) String key,
        @JsonAlias("source_column") @NotBlank @Size(max = 128) String sourceColumn
    ) {
    }

    /**
     * 封装{@code UpdateConfig}相关的不可变数据。
     */
    public record UpdateConfigRequest(
        @NotNull Boolean enabled,
        @JsonAlias("provider_type")
        @Pattern(regexp = "database|http_json") String providerType,
        @JsonAlias("connection_config_id") @Positive Long dataSourceId,
        @JsonAlias("endpoint_url") @Size(max = 1024) String endpointUrl,
        @JsonAlias("credential_ref") @Size(max = 255) String credentialRef,
        @JsonAlias("auth_type")
        @Pattern(regexp = "none|basic|bearer|header") String authType,
        @JsonAlias("credential_header") @Size(max = 64) String credentialHeader,
        @JsonAlias("request_method")
        @Pattern(regexp = "GET|POST") String requestMethod,
        @JsonAlias("request_headers") Map<String, String> requestHeaders,
        @JsonAlias("request_body") Map<String, Object> requestBody,
        @JsonAlias("response_items_path") @Size(max = 255) String responseItemsPath,
        @JsonAlias("table_name") @Size(max = 255) String tableName,
        @JsonAlias("field_map") @NotNull @Valid FieldMapping fieldMapping,
        @JsonAlias("extra_data_mappings") @Valid List<ExtraMapping> extraMappings,
        @JsonAlias("default_role_key")
        @Pattern(regexp = "|[A-Za-z0-9._:-]{1,100}") String defaultRoleKey,
        @NotBlank @Pattern(regexp = "off|hourly|daily|weekly") String schedule,
        @JsonAlias("expected_revision") @Positive Long expectedRevision
    ) {
    }

    /**
     * 封装{@code Preview}相关的不可变数据。
     */
    public record PreviewRequest(@Valid UpdateConfigRequest config) {
    }

    /**
     * 封装{@code Run}相关的不可变数据。
     */
    public record RunRequest(
        @JsonAlias("user_names") @Size(max = 5000) List<@NotBlank @Size(max = 30) String> userNames,
        @Valid UpdateConfigRequest config
    ) {
    }

    /**
     * 封装数据数据源Option相关的不可变数据。
     */
    public record DataSourceOption(
        Long id,
        String name,
        String dbType,
        String databaseName,
        String status
    ) {
    }

    /**
     * 封装{@code TableOption}相关的不可变数据。
     */
    public record TableOption(String schema, String name, String qualifiedName, String type) {
    }

    /**
     * 封装{@code ColumnOption}相关的不可变数据。
     */
    public record ColumnOption(String name, String type, boolean nullable, String sample) {
    }

    /**
     * 封装{@code PreviewItem}相关的不可变数据。
     */
    public record PreviewItem(
        String userName,
        String displayName,
        String email,
        String phoneNumber,
        String remark,
        String status,
        Map<String, Object> extraData,
        boolean existing,
        String action
    ) {
    }

    /**
     * 封装{@code Preview}相关的不可变数据。
     */
    public record PreviewView(
        String providerType,
        long configRevision,
        int total,
        int creates,
        int updates,
        List<PreviewItem> items,
        LocalDateTime previewedAt
    ) {
    }

    /**
     * 封装{@code RunItem}相关的不可变数据。
     */
    public record RunItem(
        String userName,
        String displayName,
        String email,
        String phoneNumber,
        String remark,
        String sourceStatus,
        Map<String, Object> extraData,
        String result,
        Long localUserId,
        String error
    ) {
    }

    /**
     * 封装{@code Run}相关的不可变数据。
     */
    public record RunView(
        Long id,
        Long retryOfRunId,
        String providerType,
        long configRevision,
        String status,
        List<String> requestedNames,
        List<RunItem> items,
        int discoveredCount,
        int selectedCount,
        int createdCount,
        int updatedCount,
        int skippedCount,
        int failedCount,
        String errorSummary,
        Long requestedBy,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        boolean retryable
    ) {
    }

    /**
     * 封装{@code SsoSync}相关的不可变数据。
     */
    public record SsoSyncRequest(
        @NotNull @Size(min = 1, max = 5000) List<@NotBlank @Size(max = 30) String> usernames
    ) {
    }
}
