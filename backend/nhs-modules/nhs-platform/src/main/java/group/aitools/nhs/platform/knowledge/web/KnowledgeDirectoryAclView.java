package group.aitools.nhs.platform.knowledge.web;

import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeDirectoryAcl;

import java.time.LocalDateTime;

/**
 * 封装知识库目录Acl相关的不可变数据。
 */
public record KnowledgeDirectoryAclView(
    Long id,
    Long knowledgeBaseId,
    Long directoryId,
    Long userId,
    String permission,
    String effect,
    boolean inheritChildren,
    Long revision,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public static KnowledgeDirectoryAclView from(AgentKnowledgeDirectoryAcl value) {
        return new KnowledgeDirectoryAclView(
            value.getId(), value.getKnowledgeBaseId(), value.getDirectoryId(), value.getUserId(),
            value.getPermission(), value.getEffect(), Boolean.TRUE.equals(value.getInheritChildren()),
            value.getRevisionNo(), value.getCreatedAt(), value.getUpdatedAt()
        );
    }
}
