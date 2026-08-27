package group.aitools.nhs.platform.data.web;

import java.time.LocalDateTime;

/** Metadata synchronization result. */
public record MetadataSyncView(
    Long datasetId,
    int tableCount,
    int columnCount,
    LocalDateTime synchronizedAt
) {
}
