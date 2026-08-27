package group.aitools.nhs.platform.data.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** Versioned business metric definition. */
@Data
public class AgentDataMetric {

    private Long id;
    private Long datasetId;
    private String metricKey;
    private String name;
    private String description;
    private String calculationLogic;
    private String unit;
    private String status;
    private Integer versionNo;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
