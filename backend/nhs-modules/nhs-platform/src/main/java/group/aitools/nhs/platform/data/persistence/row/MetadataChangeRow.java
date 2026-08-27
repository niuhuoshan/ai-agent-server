package group.aitools.nhs.platform.data.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/** Durable before/after snapshot for one metadata governance mutation. */
@Data
public class MetadataChangeRow {

    private Long id;
    private Long datasetId;
    private String resourceType;
    private Long resourceId;
    private String action;
    private String beforeJson;
    private String afterJson;
    private String beforeHash;
    private String afterHash;
    private Long actorId;
    private LocalDateTime createdAt;
}
