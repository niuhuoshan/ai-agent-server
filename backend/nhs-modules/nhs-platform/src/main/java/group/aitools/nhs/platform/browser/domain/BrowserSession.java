package group.aitools.nhs.platform.browser.domain;

import java.time.LocalDateTime;

/**
 * 封装浏览器会话相关的不可变数据。
 * Owner-scoped browser session persisted by the platform. */
public record BrowserSession(
    Long id,
    Long ownerId,
    String sessionKey,
    String workerSessionId,
    String profileKey,
    String status,
    String currentUrl,
    String pageTitle,
    String activeTabId,
    String tabStateJson,
    String handoffStatus,
    String handoffReason,
    Long handoffUserId,
    LocalDateTime handoffRequestedAt,
    LocalDateTime handoffStartedAt,
    LocalDateTime handoffReturnedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime closedAt
) {
}
