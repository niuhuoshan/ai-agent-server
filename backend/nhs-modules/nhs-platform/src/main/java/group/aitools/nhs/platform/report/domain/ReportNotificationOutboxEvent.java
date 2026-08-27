package group.aitools.nhs.platform.report.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示报表通知Outbox相关的领域对象。
 * Report notification event stored in the platform outbox. */
@Data
public class ReportNotificationOutboxEvent {

    private Long id;
    private String eventType;
    private Long aggregateId;
    private String eventKey;
    private String payloadJson;
    private String status;
    private Integer attemptNo;
    private LocalDateTime nextAttemptAt;
    private String lastError;
    private LocalDateTime createdAt;
}
