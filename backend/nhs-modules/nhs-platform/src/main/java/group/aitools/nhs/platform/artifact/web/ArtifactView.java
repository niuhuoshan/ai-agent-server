package group.aitools.nhs.platform.artifact.web;

import group.aitools.nhs.platform.artifact.domain.AgentArtifact;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装制品相关的不可变数据。
 * Authorized artifact version projection. */
public record ArtifactView(
    Long id,
    Long projectId,
    Long taskId,
    Long runId,
    Long stepId,
    String artifactType,
    String name,
    Integer versionNo,
    String storageType,
    String storageRef,
    String mimeType,
    Long sizeBytes,
    String contentHash,
    String sensitiveLevel,
    String visibility,
    String status,
    Map<String, Object> metadata,
    Long createdBy,
    LocalDateTime createdAt
) {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param artifact 制品参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static ArtifactView from(AgentArtifact artifact, JsonMapper jsonMapper) {
        Map<String, Object> metadata = artifact.getMetadataJson() == null
            ? Map.of()
            : jsonMapper.readValue(artifact.getMetadataJson(), MAP_TYPE);
        return new ArtifactView(
            artifact.getId(), artifact.getProjectId(), artifact.getTaskId(), artifact.getRunId(),
            artifact.getStepId(), artifact.getArtifactType(), artifact.getName(),
            artifact.getVersionNo(), artifact.getStorageType(), artifact.getStorageRef(),
            artifact.getMimeType(), artifact.getSizeBytes(), artifact.getContentHash(),
            artifact.getSensitiveLevel(), artifact.getVisibility(), artifact.getStatus(),
            metadata, artifact.getCreatedBy(), artifact.getCreatedAt()
        );
    }
}
