package group.aitools.nhs.platform.portal.dashboard.service;

import java.util.List;

/**
 * 处理快照并返回对应结果。
 *
 * 定义门户Online会话相关的处理能力契约。
 * Reads the active NHS login sessions used by the dashboard online-users compatibility route. */
public interface PortalOnlineSessionProvider {

    SessionSnapshot snapshot();

    /**
     * 封装会话快照相关的不可变数据。
     */
    record SessionSnapshot(
        boolean available,
        List<OnlineSession> sessions,
        String reason
    ) {
        /**
         * 创建 {@code SessionSnapshot} 实例并初始化所需依赖。
         *
         * @param available {@code available}参数
         * @param sessions {@code sessions}参数
         * @param reason {@code reason}参数
         */
        public SessionSnapshot {
            sessions = sessions == null ? List.of() : List.copyOf(sessions);
        }

        /**
         * 处理{@code available}并返回对应结果。
         *
         * @param sessions {@code sessions}参数
         * @return 处理结果
         */
        public static SessionSnapshot available(List<OnlineSession> sessions) {
            return new SessionSnapshot(true, sessions, null);
        }

        /**
         * 处理{@code unavailable}并返回对应结果。
         *
         * @param reason {@code reason}参数
         * @return 处理结果
         */
        public static SessionSnapshot unavailable(String reason) {
            return new SessionSnapshot(false, List.of(), reason);
        }
    }

    /**
     * 封装Online会话相关的不可变数据。
     */
    record OnlineSession(
        String userName,
        String deptName,
        String clientKey,
        String deviceType,
        Long loginTime
    ) {
    }
}
