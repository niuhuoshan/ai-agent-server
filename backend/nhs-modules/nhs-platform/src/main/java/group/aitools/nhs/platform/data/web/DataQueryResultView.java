package group.aitools.nhs.platform.data.web;

import java.util.List;

/** Bounded tabular query result. */
public record DataQueryResultView(
    Long queryId,
    List<String> columns,
    List<List<Object>> rows,
    long rowCount,
    long resultBytes,
    boolean truncated,
    long elapsedMs,
    String resultHash
) {

    /** Keeps non-report callers source-compatible while result lineage rolls out. */
    public DataQueryResultView(
        Long queryId,
        List<String> columns,
        List<List<Object>> rows,
        long rowCount,
        long resultBytes,
        boolean truncated,
        long elapsedMs
    ) {
        this(queryId, columns, rows, rowCount, resultBytes, truncated, elapsedMs, null);
    }
}
