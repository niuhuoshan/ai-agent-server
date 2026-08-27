package group.aitools.nhs.platform.knowledge.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 封装Put知识库目录Acl相关的不可变数据。
 * Creates or updates one user ACL; null directoryId means the knowledge-base root. */
public record PutKnowledgeDirectoryAclRequest(
    @Positive Long directoryId,
    @NotNull @Positive Long userId,
    @NotBlank String permission,
    @NotBlank String effect,
    boolean inheritChildren,
    @Positive Long expectedRevision
) {
}
