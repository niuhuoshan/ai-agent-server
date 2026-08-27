package group.aitools.nhs.platform.identity.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示Service账户相关的领域对象。
 * Non-human principal used by automation and API integrations. */
@Data
public class ServiceAccount {

    private Long id;
    private String accountKey;
    private String name;
    private String description;
    private Long ownerId;
    private String status;
    private LocalDateTime lastUsedAt;
    private LocalDateTime expiresAt;
    private String metadataJson;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private String delFlag;
    private String extraJson;
}
