package group.aitools.nhs.platform.data.web;

import java.util.List;

/** Executable read-only query plan after SQL and catalog validation. */
public record DataQueryValidationView(
    Long datasetId,
    List<String> tables,
    List<String> columns,
    String sqlHash,
    int maxRows,
    int statementTimeoutMs,
    int maxResultBytes
) {
}
