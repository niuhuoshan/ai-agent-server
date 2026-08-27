package group.aitools.nhs.platform.knowledge.web;

import java.util.List;

/**
 * 封装知识库Retrieval相关的不可变数据。
 */
public record KnowledgeRetrievalView(
    String status,
    String content,
    List<KnowledgeCitationView> citations
) {
}
