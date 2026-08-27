package group.aitools.nhs.platform.task.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体任务版本相关的领域对象。
 * Immutable task version used by future executions. */
@Data
@TableName("agent_task_version")
public class AgentTaskVersion {

    @TableId
    private Long id;
    private Long taskId;
    private Integer versionNo;
    private String title;
    private String objective;
    private Long agentVersionId;
    private Long workflowVersionId;
    private String contextSnapshotJson;
    private String resourceSnapshotJson;
    private String acceptanceSnapshotJson;
    private String inputSnapshotJson;
    private String contentHash;
    private Long createdBy;
    private LocalDateTime createdAt;
}
