package group.aitools.nhs.platform.identity.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示接口凭据Authentication相关的领域对象。
 * Joined credential, application and service-account state used during API authentication. */
@Data
public class ApiCredentialAuthenticationRow {

    private Long credentialId;
    private Long applicationId;
    private String appKey;
    private String applicationType;
    private String applicationStatus;
    private String applicationScopeJson;
    private LocalDateTime applicationExpiresAt;
    private Long serviceAccountId;
    private String accountKey;
    private String accountName;
    private String serviceAccountStatus;
    private LocalDateTime serviceAccountExpiresAt;
    private String credentialScopeJson;
    private LocalDateTime credentialExpiresAt;
    private LocalDateTime revokedAt;
}
