package group.aitools.nhs.platform.knowledge.web;

import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeDocument;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 封装知识库文档相关的不可变数据。
 */
public record KnowledgeDocumentView(
    Long id,
    Long knowledgeBaseId,
    String documentKey,
    String name,
    Long directoryId,
    String contentHash,
    String mimeType,
    Long sizeBytes,
    String parserType,
    String status,
    Integer chunkCount,
    Map<String, Object> metadata,
    String errorSummary,
    Long revision,
    LocalDateTime parseStartedAt,
    LocalDateTime processedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    List<String> tags,
    String remark,
    Long catalogRevision
) {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> TAGS_TYPE = new TypeReference<>() {
    };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param source 数据源参数
     * @param mapper {@code mapper}参数
     * @return 处理结果
     */
    public static KnowledgeDocumentView from(AgentKnowledgeDocument source, JsonMapper mapper) {
        Map<String, Object> metadata = mapper.readValue(source.getMetadataJson(), MAP_TYPE);
        if (metadata == null) {
            metadata = Map.of();
        }
        return new KnowledgeDocumentView(
            source.getId(), source.getKnowledgeBaseId(), source.getDocumentKey(), source.getName(),
            source.getDirectoryId(), source.getContentHash(), source.getMimeType(), source.getSizeBytes(),
            source.getParserType(), source.getStatus(), source.getChunkCount(), metadata,
            source.getErrorSummary(), source.getRevisionNo(), source.getParseStartedAt(),
            source.getProcessedAt(), source.getCreatedAt(), source.getUpdatedAt(),
            tags(source, metadata, mapper),
            source.getRemark() == null ? text(metadata.get("remark")) : source.getRemark(),
            source.getCatalogRevisionNo() == null ? 1L : source.getCatalogRevisionNo()
        );
    }

    /**
     * 处理{@code tags}并返回对应结果。
     *
     * @param source 数据源参数
     * @param metadata 元数据参数
     * @param mapper {@code mapper}参数
     * @return 符合条件的数据集合
     */
    private static List<String> tags(
        AgentKnowledgeDocument source,
        Map<String, Object> metadata,
        JsonMapper mapper
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (source.getTagsJson() != null && !source.getTagsJson().isBlank()) {
            List<String> stored = mapper.readValue(source.getTagsJson(), TAGS_TYPE);
            if (stored != null && !stored.isEmpty()) {
                return List.copyOf(stored);
            }
        }
        Object value = metadata.get("tags");
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof String tag && !tag.isBlank()) {
                result.add(tag);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private static String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }
}
