package group.aitools.nhs.platform.agent.web;

/**
 * 封装智能体Onboarding相关的不可变数据。
 * Durable result returned for both a new onboarding request and its replay. */
public record AgentOnboardingResult(
    AgentView agent,
    AgentVersionView version,
    String onboardingStep,
    boolean replayed,
    boolean templateFallback
) {
}
