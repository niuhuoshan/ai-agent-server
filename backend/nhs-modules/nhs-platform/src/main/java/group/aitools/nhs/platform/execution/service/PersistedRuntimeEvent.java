package group.aitools.nhs.platform.execution.service;

import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;

/**
 * 封装Persisted运行时相关的不可变数据。
 * Internal event pair: raw sanitized runtime payload plus its durable public projection. */
public record PersistedRuntimeEvent(RuntimeEvent source, ExecutionEventView view) {

    public PersistedRuntimeEvent {
        if (source == null || view == null) {
            throw new IllegalArgumentException("source and view are required");
        }
    }
}
