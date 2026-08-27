package group.aitools.nhs.platform.report.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 封装Update报表相关的不可变数据。
 */
public record UpdateReportRequest(
    @NotBlank @Size(max = 255) String name,
    @NotNull @Positive Long datasetId,
    @NotBlank @Size(max = 65536) String sqlTemplate,
    @Size(max = 32768) String paramsSchemaJson,
    @NotBlank @Pattern(regexp = "draft|active|disabled") String status,
    @NotBlank @Pattern(regexp = "private|enterprise_shared|restricted") String visibility
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的报表字段：" + field);
    }
}
