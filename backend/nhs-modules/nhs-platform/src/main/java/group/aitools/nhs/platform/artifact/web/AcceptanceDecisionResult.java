package group.aitools.nhs.platform.artifact.web;

/**
 * 封装验收Decision相关的不可变数据。
 * Idempotent acceptance submission result and resulting task state. */
public record AcceptanceDecisionResult(
    AcceptanceView acceptance,
    String taskStatus,
    boolean replayed
) {
}
