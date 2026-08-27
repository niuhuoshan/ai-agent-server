package group.aitools.nhs.platform.data.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Full replacement of mutable data source configuration with optimistic locking. */
public record UpdateDataSourceRequest(
    @NotNull @Positive Integer revisionNo,
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Pattern(regexp = "postgresql|mysql|oracle|sqlserver|clickhouse") String dbType,
    @NotBlank @Size(max = 1024) String endpointUrl,
    @NotBlank @Size(max = 63) String databaseName,
    @Size(max = 255) String credentialRef,
    Map<String, Object> config,
    @NotBlank @Pattern(regexp = "active|disabled") String status,
    @NotNull @Min(1000) @Max(30000) Integer connectionTimeoutMs,
    @NotNull @Min(1000) @Max(120000) Integer statementTimeoutMs,
    @NotNull @Min(1) @Max(5000) Integer maxRows,
    @NotNull @Min(1024) @Max(10485760) Integer maxResultBytes
) {

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的数据源字段：" + field);
    }
}
