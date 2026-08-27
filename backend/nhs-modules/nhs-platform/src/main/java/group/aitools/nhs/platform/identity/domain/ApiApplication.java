package group.aitools.nhs.platform.identity.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 启动并初始化接口应用运行环境。
 * Registered integration application whose scope caps every issued credential. */
@Data
public class ApiApplication {

    private Long id;
    private String appKey;
    private String name;
    private String appType;
    private String status;
    private Long ownerId;
    private String callbackUrl;
    private String scopeJson;
    private LocalDateTime expiresAt;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private String delFlag;
    private String extraJson;
}
