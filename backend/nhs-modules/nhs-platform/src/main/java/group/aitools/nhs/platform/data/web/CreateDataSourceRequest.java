package group.aitools.nhs.platform.data.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Creates one governed read-only JDBC connection definition. */
public record CreateDataSourceRequest(
    @NotBlank @Size(max = 128) @Pattern(regexp = "[a-z0-9][a-z0-9._-]*") String sourceKey,
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Pattern(regexp = "postgresql|mysql|oracle|sqlserver|clickhouse") String dbType,
    @NotBlank @Size(max = 1024) String endpointUrl,
    @NotBlank @Size(max = 63) String databaseName,
    @NotBlank @Size(max = 255) String credentialRef,
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
