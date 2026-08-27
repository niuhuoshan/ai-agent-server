package group.aitools.nhs.platform.nhs.portal.chatbi;

import java.util.Map;

/**
 * 定义门户对话BIProgressSink相关能力的服务契约。
 * Receives already-persisted ChatBI progress facts for streaming projection. */
@FunctionalInterface
public interface PortalChatBIProgressSink {

    PortalChatBIProgressSink NOOP = ignored -> { };

    /**
     * 处理{@code emit}相关逻辑。
     *
     * @param event 事件参数
     */
    void emit(Map<String, Object> event);
}
