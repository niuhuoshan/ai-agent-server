package group.aitools.nhs.platform.knowledge.web;

import java.util.Map;

/**
 * 封装知识库Citation相关的不可变数据。
 */
public record KnowledgeCitationView(
    String id,
    Long chunkId,
    Long knowledgeBaseId,
    Long documentId,
    String documentName,
    Integer chunkNo,
    double similarity,
    String content,
    Map<String, Object> metadata
) {
}
