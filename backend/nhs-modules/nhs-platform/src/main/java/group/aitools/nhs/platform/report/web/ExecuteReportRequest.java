package group.aitools.nhs.platform.report.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.Map;

/**
 * 封装Execute报表相关的不可变数据。
 */
public record ExecuteReportRequest(Map<String, Object> parameters) {

    /**
     * 创建 {@code ExecuteReportRequest} 实例并初始化所需依赖。
     *
     * @param parameters {@code parameters}参数
     */
    public ExecuteReportRequest {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的报表执行字段：" + field);
    }
}
