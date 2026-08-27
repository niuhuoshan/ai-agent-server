package group.aitools.nhs.platform.embed.web;

import jakarta.validation.constraints.Positive;

/**
 * 封装嵌入式会话浏览器会话相关的不可变数据。
 * Identifies an owner-scoped browser session controlled by an Embed credential. */
public record EmbedBrowserSessionRequest(@Positive Long browserSessionId) {
}
