package group.aitools.nhs.platform.connector.web;

/**
 * 封装运行时ConfirmationDecision相关的不可变数据。
 * Idempotent business-confirmation decision result. */
public record RuntimeConfirmationDecisionResult(
    RuntimeConfirmationView confirmation,
    boolean replayed,
    boolean resumed
) {
}
