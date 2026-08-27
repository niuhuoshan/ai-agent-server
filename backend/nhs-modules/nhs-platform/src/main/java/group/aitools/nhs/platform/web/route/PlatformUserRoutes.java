package group.aitools.nhs.platform.web.route;

import java.util.List;

/**
 * 封装平台用户Routes相关的不可变数据。
 * Dynamic routes and the current user's home route. */
public record PlatformUserRoutes(List<PlatformRoute> routes, String home) {
}
