package group.aitools.nhs.platform.identity.domain;

import lombok.Data;

/**
 * 表示身份SyncLocal用户相关的领域对象。
 * Minimal local NHS user projection used by identity synchronization. */
@Data
public class IdentitySyncLocalUser {

    private Long userId;
    private String userName;
    private String nickName;
    private String email;
    private String phoneNumber;
    private String status;
}
