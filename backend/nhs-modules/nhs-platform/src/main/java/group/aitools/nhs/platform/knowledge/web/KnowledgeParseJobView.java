package group.aitools.nhs.platform.knowledge.web;

/**
 * 封装知识库Parse作业相关的不可变数据。
 */
public record KnowledgeParseJobView(
    Long jobId,
    Long documentId,
    Long documentRevision,
    String status
) {
}
