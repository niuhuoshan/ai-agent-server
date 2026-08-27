package group.aitools.nhs.platform.knowledge.web;

/**
 * 封装知识库提供方Status相关的不可变数据。
 * Credential-free readiness projection for local and external knowledge providers. */
public record KnowledgeProviderStatusView(
    String providerType,
    boolean available,
    String state,
    String message
) {
}
