package group.aitools.nhs.platform.notification.persistence.row;

import lombok.Data;

/**
 * 表示任务通知Owner相关的领域对象。
 * Typed task owner used only for notification routing. */
@Data
public class TaskNotificationOwnerRow {

    private Long ownerId;
    private String ownerPrincipalType;
}
