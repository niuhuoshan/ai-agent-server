package group.aitools.nhs.platform.connector.web;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 封装工具OnlineTest相关的不可变数据。
 * Explicit administrator request for a bounded online tool test. */
public record ToolOnlineTestRequest(
    @NotNull Map<String, Object> arguments,
    boolean confirmRisk
) {
}
