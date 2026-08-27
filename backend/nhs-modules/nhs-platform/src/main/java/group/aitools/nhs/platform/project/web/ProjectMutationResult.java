package group.aitools.nhs.platform.project.web;

/**
 * 封装项目Mutation相关的不可变数据。
 * Idempotent project creation result. */
public record ProjectMutationResult(ProjectView project, boolean replayed) {
}
