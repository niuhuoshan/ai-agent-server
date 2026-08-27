package group.aitools.nhs.platform.operations.web;

import group.aitools.nhs.platform.operations.domain.PlatformConfiguration;

import java.time.LocalDateTime;

/**
 * 封装平台配置相关的不可变数据。
 */
public record PlatformConfigurationView(
    String productName,
    String productShortName,
    String logoUrl,
    String faviconUrl,
    String primaryColor,
    String platformTimezone,
    String defaultLocale,
    boolean watermarkEnabled,
    Long revisionNo,
    Long updatedBy,
    LocalDateTime updatedAt
) {
    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public static PlatformConfigurationView from(PlatformConfiguration value) {
        return new PlatformConfigurationView(
            value.getProductName(), value.getProductShortName(), value.getLogoUrl(),
            value.getFaviconUrl(), value.getPrimaryColor(), value.getPlatformTimezone(),
            value.getDefaultLocale(), Boolean.TRUE.equals(value.getWatermarkEnabled()),
            value.getRevisionNo(), value.getUpdateBy(), value.getUpdateTime()
        );
    }
}
