package group.aitools.nhs.platform.data.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** Governed join relationship between two synchronized tables. */
@Data
public class AgentDataRelation {

    private Long id;
    private Long datasetId;
    private Long sourceTableId;
    private Long targetTableId;
    private String joinType;
    private String joinCondition;
    private String description;
    private String status;
    private Integer revisionNo;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
