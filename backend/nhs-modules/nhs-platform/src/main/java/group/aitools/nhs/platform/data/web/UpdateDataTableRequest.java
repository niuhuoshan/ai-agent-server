package group.aitools.nhs.platform.data.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Platform-managed labels and query availability for a synchronized table. */
public record UpdateDataTableRequest(
    @NotBlank @Size(max = 255) String displayName,
    @Size(max = 4000) String description,
    @NotBlank @Pattern(regexp = "active|inactive") String status
) {

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的数据表字段：" + field);
    }
}
