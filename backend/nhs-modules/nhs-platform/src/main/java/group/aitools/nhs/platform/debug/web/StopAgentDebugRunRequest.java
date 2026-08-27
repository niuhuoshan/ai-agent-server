package group.aitools.nhs.platform.debug.web;

import jakarta.validation.constraints.Size;

/**
 * 封装Stop智能体DebugRun相关的不可变数据。
 * Optional operator reason for a durable debugger stop. */
public record StopAgentDebugRunRequest(@Size(max = 2000) String reason) {
}
