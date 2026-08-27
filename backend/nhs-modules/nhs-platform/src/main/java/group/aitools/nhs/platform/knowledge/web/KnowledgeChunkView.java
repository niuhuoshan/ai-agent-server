package group.aitools.nhs.platform.knowledge.web;

import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeChunk;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装知识库Chunk相关的不可变数据。
 * Public chunk detail without exposing the persisted vector payload. */
public record KnowledgeChunkView(
    Long id,
    Long knowledgeBaseId,
    Long documentId,
    Integer chunkNo,
    String content,
    String contentHash,
    Integer tokenCount,
    String status,
    Map<String, Object> metadata,
    LocalDateTime createdAt
) {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param source 数据源参数
     * @param mapper {@code mapper}参数
     * @return 处理结果
     */
    public static KnowledgeChunkView from(AgentKnowledgeChunk source, JsonMapper mapper) {
        Map<String, Object> metadata = mapper.readValue(source.getMetadataJson(), MAP_TYPE);
        return new KnowledgeChunkView(
            source.getId(), source.getKnowledgeBaseId(), source.getDocumentId(), source.getChunkNo(),
            source.getContent(), source.getContentHash(), source.getTokenCount(), source.getStatus(),
            metadata == null ? Map.of() : metadata, source.getCreatedAt()
        );
    }
}
