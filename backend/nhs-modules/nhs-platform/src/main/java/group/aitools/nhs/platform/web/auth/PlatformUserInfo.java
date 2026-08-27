package group.aitools.nhs.platform.web.auth;

import java.util.List;

/**
 * 封装平台用户Info相关的不可变数据。
 * User identity shape consumed by SoybeanAdmin. */
public record PlatformUserInfo(
    String userId,
    String userName,
    List<String> roles,
    List<String> buttons
) {
}
