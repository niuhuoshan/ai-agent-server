package group.aitools.nhs.platform.execution.web;

import jakarta.validation.constraints.Size;

/**
 * 封装Pause任务Run相关的不可变数据。
 * Optional operator reason recorded on a paused run. */
public record PauseTaskRunRequest(@Size(max = 2000) String reason) {
}
