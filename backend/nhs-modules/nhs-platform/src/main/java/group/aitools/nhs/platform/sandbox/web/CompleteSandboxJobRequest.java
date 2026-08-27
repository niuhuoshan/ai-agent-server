package group.aitools.nhs.platform.sandbox.web;

import java.util.List;
import java.util.Map;

/**
 * 封装Complete沙箱作业相关的不可变数据。
 */
public record CompleteSandboxJobRequest(
    Boolean succeeded,
    Integer exitCode,
    String stdout,
    String stderr,
    List<Map<String, Object>> outputManifest,
    Map<String, Object> resourceUsage,
    String failureCode,
    String failureMessage
) {
}
