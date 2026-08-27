package group.aitools.nhs.platform.embed.web;

import java.util.List;

/**
 * 封装嵌入式会话WidgetState相关的不可变数据。
 */
public record EmbedWidgetStateView(
    EmbedSessionView session,
    List<EmbedTurnView> turns,
    List<EmbedMessageView> messages
) {
    /**
     * 创建 {@code EmbedWidgetStateView} 实例并初始化所需依赖。
     *
     * @param session 会话参数
     * @param turns {@code turns}参数
     * @param messages 待处理内容
     */
    public EmbedWidgetStateView {
        turns = List.copyOf(turns);
        messages = List.copyOf(messages);
    }
}
