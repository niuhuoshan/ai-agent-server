package group.aitools.nhs.platform.report.persistence.row;

import lombok.Data;

/**
 * 表示报表执行操作主体相关的领域对象。
 * Current NHS identity state used to re-authorize a scheduled report execution. */
@Data
public class ReportExecutionPrincipalRow {

    private Long userId;
    private String userName;
    private String userType;
    private String status;
    private String delFlag;
}
