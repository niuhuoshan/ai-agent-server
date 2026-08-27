package group.aitools.nhs.platform.data.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Column labels, sensitivity classification and query availability. */
public record UpdateDataColumnRequest(
    @NotBlank @Size(max = 255) String displayName,
    @Size(max = 4000) String description,
    @NotNull Boolean sensitive,
    @NotBlank @Pattern(regexp = "active|inactive") String status
) {

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的数据列字段：" + field);
    }
}
