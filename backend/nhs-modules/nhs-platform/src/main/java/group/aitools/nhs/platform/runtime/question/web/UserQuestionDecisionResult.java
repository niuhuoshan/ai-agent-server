package group.aitools.nhs.platform.runtime.question.web;

/**
 * 封装用户追问Decision相关的不可变数据。
 * Result of an answer/cancel request, including whether it was an idempotent replay. */
public record UserQuestionDecisionResult(
    UserQuestionView question,
    boolean replayed,
    boolean resumed
) {

    /**
     * 创建 {@code UserQuestionDecisionResult} 实例并初始化所需依赖。
     *
     * @param question 追问参数
     * @param replayed {@code replayed}参数
     */
    public UserQuestionDecisionResult(UserQuestionView question, boolean replayed) {
        this(question, replayed, false);
    }
}
