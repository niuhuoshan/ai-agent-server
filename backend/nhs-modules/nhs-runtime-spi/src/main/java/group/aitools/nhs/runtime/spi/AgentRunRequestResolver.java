package group.aitools.nhs.runtime.spi;

/**
 * 获取{@code ForResume}。
 *
 * 定义智能体RunRequest相关的处理能力契约。
 * Loads the immutable runtime definition persisted when an execution was first created. */
@FunctionalInterface
public interface AgentRunRequestResolver {

    AgentRunRequest resolveForResume(AgentResumeRequest request);
}
