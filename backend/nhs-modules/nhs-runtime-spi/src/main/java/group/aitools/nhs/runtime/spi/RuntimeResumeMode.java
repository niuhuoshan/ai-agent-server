package group.aitools.nhs.runtime.spi;

/**
 * 定义运行时ResumeMode相关的可选值。
 * Describes why a persisted AgentScope session is being resumed. */
public enum RuntimeResumeMode {
    /** Resume a pending tool confirmation using the server-owned action snapshot. */
    APPROVAL,
    /** Resume a suspended schema-only tool with Runner-owned execution results. */
    EXTERNAL_EXECUTION,
    /** Continue a manually paused or externally interrupted session. */
    CONTINUE
}
