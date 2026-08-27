package group.aitools.nhs.platform.operations.web;

import group.aitools.nhs.platform.operations.domain.PlatformConfiguration;

/**
 * 封装Public平台配置相关的不可变数据。
 */
public record PublicPlatformConfigurationView(
    String productName,
    String productShortName,
    String logoUrl,
    String faviconUrl,
    String primaryColor,
    String platformTimezone,
    String defaultLocale,
    boolean watermarkEnabled
) {
    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public static PublicPlatformConfigurationView from(PlatformConfiguration value) {
        return new PublicPlatformConfigurationView(
            value.getProductName(), value.getProductShortName(), value.getLogoUrl(),
            value.getFaviconUrl(), value.getPrimaryColor(), value.getPlatformTimezone(),
            value.getDefaultLocale(), Boolean.TRUE.equals(value.getWatermarkEnabled())
        );
    }
}
