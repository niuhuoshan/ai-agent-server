package group.aitools.nhs.platform.execution.persistence.row;

import lombok.Data;

/**
 * 表示会话任务Run相关的领域对象。
 * Active task run rooted in a private conversation, used by global cancel. */
@Data
public class ConversationTaskRunRow {

    private Long taskId;
    private Long runId;
    private String traceId;
    private String status;
}
