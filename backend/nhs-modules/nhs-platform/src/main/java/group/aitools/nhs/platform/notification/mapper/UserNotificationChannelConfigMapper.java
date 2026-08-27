package group.aitools.nhs.platform.notification.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import group.aitools.nhs.platform.notification.domain.UserNotificationChannelConfig;

import java.util.List;

/**
 * 获取By用户。
 *
 * 定义用户通知ChannelConfig相关的数据访问契约。
 * Explicit owner-scoped persistence for personal notification channel configuration. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface UserNotificationChannelConfigMapper {

    @Select("""
        SELECT id, user_id, channel_type, is_enabled AS enabled,
               CAST(config_json AS text) AS config_json, secret_payload,
               created_at, updated_at
        FROM agent_user_notification_channel
        WHERE user_id = #{userId}
        ORDER BY channel_type ASC
        """)
    List<UserNotificationChannelConfig> selectByUser(@Param("userId") Long userId);

    /**
     * 获取{@code One}。
     *
     * @param userId 资源标识
     * @param channelType 业务类型
     * @return 处理结果
     */
    @Select("""
        SELECT id, user_id, channel_type, is_enabled AS enabled,
               CAST(config_json AS text) AS config_json, secret_payload,
               created_at, updated_at
        FROM agent_user_notification_channel
        WHERE user_id = #{userId} AND channel_type = #{channelType}
        """)
    UserNotificationChannelConfig selectOne(
        @Param("userId") Long userId,
        @Param("channelType") String channelType
    );

    /**
     * 处理{@code upsert}并返回对应结果。
     *
     * @param config {@code config}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_user_notification_channel (
            id, user_id, channel_type, is_enabled, config_json,
            secret_payload, created_at, updated_at
        ) VALUES (
            #{id}, #{userId}, #{channelType}, #{enabled}, CAST(#{configJson} AS jsonb),
            #{secretPayload}, #{createdAt}, #{updatedAt}
        )
        ON CONFLICT (user_id, channel_type) DO UPDATE
        SET is_enabled = EXCLUDED.is_enabled,
            config_json = EXCLUDED.config_json,
            secret_payload = EXCLUDED.secret_payload,
            updated_at = EXCLUDED.updated_at
        """)
    int upsert(UserNotificationChannelConfig config);
}
