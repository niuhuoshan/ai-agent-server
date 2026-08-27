package group.aitools.nhs.platform.identity.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配置智能体身份Sync相关组件及其运行参数。
 * Singleton identity-provider synchronization configuration. */
@Data
public class AgentIdentitySyncConfig {

    private Long id;
    private Boolean enabled;
    private String providerType;
    private Long dataSourceId;
    private String endpointUrl;
    private String credentialRef;
    private String authType;
    private String credentialHeader;
    private String requestMethod;
    private String requestHeadersJson;
    private String requestBodyJson;
    private String responseItemsPath;
    private String tableName;
    private String usernameColumn;
    private String displayNameColumn;
    private String emailColumn;
    private String phoneColumn;
    private String remarkColumn;
    private String statusColumn;
    private String extraMappingsJson;
    private String defaultRoleKey;
    private String schedule;
    private Long revisionNo;
    private LocalDateTime lastPreviewAt;
    private LocalDateTime lastRunAt;
    private String lastRunStatus;
    private String lastError;
    private Long updateBy;
    private LocalDateTime updateTime;
}
