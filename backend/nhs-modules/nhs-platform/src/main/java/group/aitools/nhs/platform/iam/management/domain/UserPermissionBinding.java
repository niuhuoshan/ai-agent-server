package group.aitools.nhs.platform.iam.management.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示用户权限Binding相关的领域对象。
 * Current or historical base permission binding for one human user. */
@Data
public class UserPermissionBinding {

    private Long id;
    private Long userId;
    private Long profileId;
    private Integer profileVersion;
    private String bindingType;
    private String snapshotJson;
    private Long sourceUserId;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
