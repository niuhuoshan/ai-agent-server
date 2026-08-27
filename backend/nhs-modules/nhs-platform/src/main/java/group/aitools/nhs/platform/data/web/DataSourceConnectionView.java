package group.aitools.nhs.platform.data.web;

import java.time.LocalDateTime;

/** Sanitized connectivity result. */
public record DataSourceConnectionView(
    boolean success,
    String message,
    long latencyMs,
    LocalDateTime testedAt
) {
}
