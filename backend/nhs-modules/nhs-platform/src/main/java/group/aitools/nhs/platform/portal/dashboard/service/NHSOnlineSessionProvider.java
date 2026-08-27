package group.aitools.nhs.platform.portal.dashboard.service;

import cn.dev33.satoken.stp.StpUtil;
import group.aitools.nhs.common.core.constant.CacheNames;
import group.aitools.nhs.common.core.utils.StringUtils;
import group.aitools.nhs.common.redis.utils.RedisUtils;
import group.aitools.nhs.system.api.domain.UserOnlineDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 处理快照并返回对应结果。
 *
 * 负责NHSOnline会话相关的转换、解析或处理逻辑。
 * Uses the same Redis token facts as NHS's online-user monitor. */
@Component
public class NHSOnlineSessionProvider implements PortalOnlineSessionProvider {

    @Override
    public SessionSnapshot snapshot() {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Collection<String> keys;
        try {
            keys = RedisUtils.keys(CacheNames.ONLINE_TOKEN_KEY + "*");
        } catch (RuntimeException exception) {
            return SessionSnapshot.unavailable("Redis 在线会话数据暂不可用");
        }
        List<OnlineSession> sessions = new ArrayList<>();
        for (String key : keys) {
            String token = StringUtils.substringAfterLast(key, StringUtils.COLON);
            if (token == null || token.isBlank()) {
                continue;
            }
            try {
                if (StpUtil.stpLogic.getTokenActiveTimeoutByToken(token) < -1) {
                    continue;
                }
                UserOnlineDTO online = RedisUtils.getCacheObject(
                    CacheNames.ONLINE_TOKEN_KEY + token
                );
                if (online != null) {
                    sessions.add(new OnlineSession(
                        online.getUserName(), online.getDeptName(), online.getClientKey(),
                        online.getDeviceType(), online.getLoginTime()
                    ));
                }
            } catch (RuntimeException ignored) {
                // A token can expire between SCAN and GET; skip that session.
            }
        }
        return SessionSnapshot.available(sessions);
    }
}
