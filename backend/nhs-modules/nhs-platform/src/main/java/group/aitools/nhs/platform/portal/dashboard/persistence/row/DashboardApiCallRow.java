package group.aitools.nhs.platform.portal.dashboard.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示Dashboard接口Call相关的领域对象。
 * Redacted recent machine API call projection; credentials and request IDs are excluded. */
@Data
public class DashboardApiCallRow {

    private Long id;
    private String endpointKey;
    private String httpMethod;
    private Integer statusCode;
    private Long durationMs;
    private String outcome;
    private String errorCode;
    private String username;
    private LocalDateTime createdAt;
}
