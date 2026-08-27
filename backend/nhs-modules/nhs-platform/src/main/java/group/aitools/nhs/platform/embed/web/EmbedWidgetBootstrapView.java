package group.aitools.nhs.platform.embed.web;

import group.aitools.nhs.platform.agent.web.WelcomeCardView;
import group.aitools.nhs.platform.identity.service.EmbedApplicationConfig;

import java.util.List;

/**
 * 封装嵌入式会话WidgetBootstrap相关的不可变数据。
 */
public record EmbedWidgetBootstrapView(
    EmbedSessionView session,
    EmbedBrowserCredentialView browserCredential,
    EmbedApplicationConfig config,
    List<WelcomeCardView> welcomeCards
) {
    /**
     * 创建 {@code EmbedWidgetBootstrapView} 实例并初始化所需依赖。
     *
     * @param session 会话参数
     * @param browserCredential 浏览器凭据参数
     * @param config {@code config}参数
     * @param welcomeCards {@code welcomeCards}参数
     */
    public EmbedWidgetBootstrapView {
        welcomeCards = welcomeCards == null ? List.of() : List.copyOf(welcomeCards);
    }
}
