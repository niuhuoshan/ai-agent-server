package group.aitools.nhs.platform.automation.persistence.row;

import lombok.Data;

/**
 * 表示自动化任务Target相关的领域对象。
 */
@Data
public class AutomationTaskTargetRow {
    private Long taskId;
    private Long taskVersionId;
    private Long taskRevisionNo;
}
