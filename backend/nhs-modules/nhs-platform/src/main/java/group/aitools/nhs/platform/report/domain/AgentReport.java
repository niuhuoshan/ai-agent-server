package group.aitools.nhs.platform.report.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体报表相关的领域对象。
 * Saved, parameterized read-only report definition. */
@Data
public class AgentReport {

    private Long id;
    private String reportKey;
    private String name;
    private Long datasetId;
    private String sqlTemplate;
    private String paramsSchemaJson;
    private String visibility;
    private Long ownerId;
    private String status;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private String delFlag;
    private String extraJson;
}
