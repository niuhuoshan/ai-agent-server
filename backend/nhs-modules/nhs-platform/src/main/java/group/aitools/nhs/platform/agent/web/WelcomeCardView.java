package group.aitools.nhs.platform.agent.web;

/**
 * 封装{@code WelcomeCard}相关的不可变数据。
 * One display-safe, executable prompt shown on an Agent welcome surface. */
public record WelcomeCardView(
    String icon,
    String title,
    String subtitle,
    String prompt
) {
}
