package group.aitools.nhs.platform.model.service;

/**
 * 获取{@code resolve}。
 *
 * 定义模型凭据相关的处理能力契约。
 * Reads a stored model API key only at the outbound provider boundary. */
@FunctionalInterface
public interface ModelCredentialResolver {

    String resolve(String credentialRef);
}
