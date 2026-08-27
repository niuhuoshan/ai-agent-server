package group.aitools.nhs.platform.identity.domain;

import java.util.Map;

/**
 * 封装身份SyncExternal用户相关的不可变数据。
 * Normalized, provider-independent identity row. */
public record IdentitySyncExternalUser(
    String userName,
    String displayName,
    String email,
    String phoneNumber,
    String remark,
    String status,
    Map<String, Object> extraData
) {
}
