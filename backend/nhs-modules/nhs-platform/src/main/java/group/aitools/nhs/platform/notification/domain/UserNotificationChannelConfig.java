package group.aitools.nhs.platform.notification.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配置用户通知Channel相关组件及其运行参数。
 * Encrypted, owner-scoped configuration for one personal notification channel. */
@Data
@TableName("agent_user_notification_channel")
public class UserNotificationChannelConfig {

    @TableId
    private Long id;
    private Long userId;
    private String channelType;
    private Boolean enabled;
    private String configJson;
    private String secretPayload;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
