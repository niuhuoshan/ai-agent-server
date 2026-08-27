package group.aitools.nhs.platform.knowledge.web;

import java.util.List;

/**
 * 封装知识库Tree相关的不可变数据。
 * Flat directory/document projections; parent IDs let clients build any tree shape. */
public record KnowledgeTreeView(
    List<KnowledgeDirectoryView> directories,
    List<KnowledgeDocumentView> documents
) {
}
