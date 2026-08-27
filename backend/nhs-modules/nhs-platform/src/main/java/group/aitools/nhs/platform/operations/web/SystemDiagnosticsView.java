package group.aitools.nhs.platform.operations.web;

import java.time.Instant;
import java.util.List;

/**
 * 封装系统Diagnostics相关的不可变数据。
 * Point-in-time deep diagnostics used by both the UI and readiness probe. */
public record SystemDiagnosticsView(
    String status,
    Instant checkedAt,
    List<SystemDiagnosticCheckView> checks
) {

    /**
     * 处理{@code ready}并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean ready() {
        return "healthy".equals(status);
    }
}
