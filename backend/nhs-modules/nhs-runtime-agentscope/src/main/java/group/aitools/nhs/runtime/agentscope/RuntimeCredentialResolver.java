package group.aitools.nhs.runtime.agentscope;

/**
 * 获取{@code resolve}。
 *
 * 定义运行时凭据相关的处理能力契约。
 * Resolves a secret reference inside the runtime boundary without exposing it to platform events. */
@FunctionalInterface
public interface RuntimeCredentialResolver {

    String resolve(String credentialRef);
}
