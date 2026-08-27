package group.aitools.nhs.platform.connector.service;

/**
 * 获取{@code resolve}。
 *
 * 定义连接器凭据相关的处理能力契约。
 * Resolves a secret only at the connector execution boundary. */
public interface ConnectorCredentialResolver {

    String resolve(String credentialRef);
}
