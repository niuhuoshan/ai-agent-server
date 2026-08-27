package group.aitools.nhs.platform.data.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Immutable bounded ChatBI result persisted for controlled export. */
@Data
public class DataQueryStoredResultRow {
    private Long queryId;
    private String columnsJson;
    private String rowsJson;
    private String contentHash;
    private Integer rowCount;
    private Integer resultBytes;
    private Long createdBy;
    private LocalDateTime createdAt;
}
