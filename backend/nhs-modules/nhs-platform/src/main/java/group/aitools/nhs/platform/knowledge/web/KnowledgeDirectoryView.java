package group.aitools.nhs.platform.knowledge.web;

import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeDirectory;

import java.time.LocalDateTime;

/**
 * 封装知识库目录相关的不可变数据。
 */
public record KnowledgeDirectoryView(
    Long id,
    Long knowledgeBaseId,
    Long parentId,
    String directoryKey,
    String name,
    Long revision,
    Long documentCount,
    Long childDirectoryCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    public static KnowledgeDirectoryView from(AgentKnowledgeDirectory source) {
        return new KnowledgeDirectoryView(
            source.getId(), source.getKnowledgeBaseId(), source.getParentId(), source.getDirectoryKey(),
            source.getName(), source.getRevisionNo(),
            source.getDocumentCount() == null ? 0L : source.getDocumentCount(),
            source.getChildDirectoryCount() == null ? 0L : source.getChildDirectoryCount(),
            source.getCreatedAt(), source.getUpdatedAt()
        );
    }
}
