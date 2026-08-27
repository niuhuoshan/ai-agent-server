package group.aitools.nhs.platform.artifact.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体制品相关的领域对象。
 * Immutable versioned task deliverable. */
@Data
public class AgentArtifact {

    private Long id;
    private Long projectId;
    private Long taskId;
    private Long runId;
    private Long stepId;
    private String artifactType;
    private String name;
    private Integer versionNo;
    private String storageType;
    private String storageRef;
    private String mimeType;
    private Long sizeBytes;
    private String contentHash;
    private String sensitiveLevel;
    private String visibility;
    private String status;
    private String metadataJson;
    private Long createdBy;
    private LocalDateTime createdAt;
}
