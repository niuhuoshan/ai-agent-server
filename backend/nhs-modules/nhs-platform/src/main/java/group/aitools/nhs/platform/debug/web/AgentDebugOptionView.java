package group.aitools.nhs.platform.debug.web;

import java.util.List;

/**
 * 封装智能体DebugOption相关的不可变数据。
 * Agent identity and the versions the current user may execute in the debugger. */
public record AgentDebugOptionView(
    Long id,
    String agentKey,
    String name,
    String description,
    String avatarUrl,
    boolean defaultAgent,
    Long publishedVersionId,
    List<AgentDebugVersionOptionView> versions
) {
}
