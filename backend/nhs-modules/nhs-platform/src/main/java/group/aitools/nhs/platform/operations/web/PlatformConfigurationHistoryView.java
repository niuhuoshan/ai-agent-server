package group.aitools.nhs.platform.operations.web;

import group.aitools.nhs.platform.operations.domain.PlatformConfigurationHistory;

import java.time.LocalDateTime;

/**
 * 封装平台配置历史记录相关的不可变数据。
 */
public record PlatformConfigurationHistoryView(
    Long id,
    String productName,
    String productShortName,
    String logoUrl,
    String faviconUrl,
    String primaryColor,
    String platformTimezone,
    String defaultLocale,
    boolean watermarkEnabled,
    Long revisionNo,
    String changeReason,
    Long changedBy,
    LocalDateTime createdAt
) {
    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public static PlatformConfigurationHistoryView from(PlatformConfigurationHistory value) {
        return new PlatformConfigurationHistoryView(
            value.getId(), value.getProductName(), value.getProductShortName(),
            value.getLogoUrl(), value.getFaviconUrl(), value.getPrimaryColor(),
            value.getPlatformTimezone(), value.getDefaultLocale(),
            Boolean.TRUE.equals(value.getWatermarkEnabled()), value.getRevisionNo(),
            value.getChangeReason(), value.getChangedBy(), value.getCreatedAt()
        );
    }
}
