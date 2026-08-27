package group.aitools.nhs.platform.operations.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示平台配置历史记录相关的领域对象。
 * Immutable snapshot of one platform configuration revision. */
@Data
public class PlatformConfigurationHistory {

    private Long id;
    private Long configurationId;
    private String productName;
    private String productShortName;
    private String logoUrl;
    private String faviconUrl;
    private String primaryColor;
    private String platformTimezone;
    private String defaultLocale;
    private Boolean watermarkEnabled;
    private Long revisionNo;
    private String changeReason;
    private Long changedBy;
    private LocalDateTime createdAt;
}
