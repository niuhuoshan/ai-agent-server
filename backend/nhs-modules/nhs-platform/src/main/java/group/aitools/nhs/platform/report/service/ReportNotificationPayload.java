package group.aitools.nhs.platform.report.service;

import java.util.List;

/**
 * 封装报表通知Payload相关的不可变数据。
 * Server-owned report notification payload persisted in the generic outbox. */
public record ReportNotificationPayload(
    Long recipientId,
    String eventKey,
    String level,
    String title,
    String content,
    Long reportId,
    Long reportRunId,
    Long resultQueryId,
    String resultHash,
    List<String> channels
) {

    /**
 * 创建 {@code ReportNotificationPayload} 实例并初始化所需依赖。
 * Backward-compatible constructor for outbox rows created before channel fan-out. */
    public ReportNotificationPayload(
        Long recipientId,
        String eventKey,
        String level,
        String title,
        String content,
        Long reportId
    ) {
        this(recipientId, eventKey, level, title, content, reportId, null, null, null, null);
    }

    /**
 * 创建 {@code ReportNotificationPayload} 实例并初始化所需依赖。
 * Backward-compatible constructor for payloads that already include channels. */
    public ReportNotificationPayload(
        Long recipientId,
        String eventKey,
        String level,
        String title,
        String content,
        Long reportId,
        List<String> channels
    ) {
        this(recipientId, eventKey, level, title, content, reportId, null, null, null, channels);
    }
}
