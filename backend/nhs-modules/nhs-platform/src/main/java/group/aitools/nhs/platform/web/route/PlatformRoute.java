package group.aitools.nhs.platform.web.route;

import java.util.List;

/**
 * 封装平台Route相关的不可变数据。
 * JSON-compatible route record consumed by SoybeanAdmin dynamic routing. */
public record PlatformRoute(
    String id,
    String name,
    String path,
    String component,
    Boolean props,
    PlatformRouteMeta meta,
    List<PlatformRoute> children
) {
}
