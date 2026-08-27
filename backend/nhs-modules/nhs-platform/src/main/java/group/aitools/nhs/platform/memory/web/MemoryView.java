package group.aitools.nhs.platform.memory.web;

import group.aitools.nhs.platform.memory.domain.AgentMemory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装记忆相关的不可变数据。
 */
public record MemoryView(
    Long id,
    String memoryKey,
    String scopeType,
    Long scopeId,
    String memoryType,
    String content,
    String sourceType,
    Long sourceId,
    Double confidence,
    String sensitiveLevel,
    String reviewStatus,
    LocalDateTime expiresAt,
    Map<String, Object> metadata,
    Long revisionNo,
    Long reviewedBy,
    LocalDateTime reviewedAt,
    String reviewComment,
    Long createdBy,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param memory 记忆参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static MemoryView from(AgentMemory memory, JsonMapper jsonMapper) {
        Map<String, Object> metadata = memory.getMetadataJson() == null
            ? Map.of() : jsonMapper.readValue(memory.getMetadataJson(), MAP_TYPE);
        return new MemoryView(
            memory.getId(), memory.getMemoryKey(), memory.getScopeType(), memory.getScopeId(),
            memory.getMemoryType(), memory.getContent(), memory.getSourceType(), memory.getSourceId(),
            memory.getConfidence(), memory.getSensitiveLevel(), memory.getReviewStatus(),
            memory.getExpiresAt(), metadata == null ? Map.of() : metadata, memory.getRevisionNo(),
            memory.getReviewedBy(), memory.getReviewedAt(), memory.getReviewComment(),
            memory.getCreatedBy(), memory.getCreatedAt(), memory.getUpdatedAt()
        );
    }
}
