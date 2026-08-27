package group.aitools.nhs.platform.compat.nhs;

import java.util.List;

/**
 * 封装Nhs用户配置档案相关的不可变数据。
 * Nhs V1 user profile projection backed by the platform principal. */
public record NhsUserProfile(
    Long id,
    String username,
    String display_name,
    String role,
    int status,
    String api_key,
    List<String> roles,
    List<String> permissions,
    List<String> unsupported
) {
}
