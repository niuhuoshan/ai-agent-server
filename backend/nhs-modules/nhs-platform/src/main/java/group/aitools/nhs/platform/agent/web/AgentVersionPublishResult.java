package group.aitools.nhs.platform.agent.web;

/**
 * 封装智能体版本Publish相关的不可变数据。
 * Idempotent publish outcome. */
public record AgentVersionPublishResult(AgentVersionView version, boolean replayed) {
}
