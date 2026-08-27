package group.aitools.nhs.platform.operations.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配置平台相关组件及其运行参数。
 * Current private-deployment branding, locale and timezone configuration. */
@Data
public class PlatformConfiguration {

    private Long id;
    private String productName;
    private String productShortName;
    private String logoUrl;
    private String faviconUrl;
    private String primaryColor;
    private String platformTimezone;
    private String defaultLocale;
    private Boolean watermarkEnabled;
    private Long revisionNo;
    private Long updateBy;
    private LocalDateTime updateTime;
}
