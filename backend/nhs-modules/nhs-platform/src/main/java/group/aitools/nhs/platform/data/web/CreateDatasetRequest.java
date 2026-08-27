package group.aitools.nhs.platform.data.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Creates a dataset over explicitly selected schemas or catalog-scoped databases. */
public record CreateDatasetRequest(
    @NotNull @Positive Long dataSourceId,
    @NotBlank @Size(max = 128) @Pattern(regexp = "[a-z0-9][a-z0-9._-]*") String datasetKey,
    @NotBlank @Size(max = 255) String name,
    @Size(max = 4000) String description,
    @NotEmpty @Size(max = 16) List<@NotBlank @Size(max = 63) String> schemaNames,
    @NotBlank @Pattern(regexp = "active|disabled") String status
) {

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的数据集字段：" + field);
    }
}
