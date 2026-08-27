package group.aitools.nhs.platform.web.route;

/**
 * 封装平台RouteMeta相关的不可变数据。
 * JSON-compatible route metadata consumed by SoybeanAdmin. */
public record PlatformRouteMeta(
    String title,
    String i18nKey,
    String icon,
    Integer order,
    Boolean constant,
    Boolean hideInMenu
) {
}
