package group.aitools.nhs.platform.task.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体任务相关的领域对象。
 * Formal task control-plane record. */
@Data
@TableName("agent_task")
public class AgentTask {

    @TableId
    private Long id;
    private String taskKey;
    private Long projectId;
    private String title;
    private String objective;
    private String background;
    private Long sourceConversationId;
    private String contextSnapshotJson;
    private String visibility;
    private String category;
    private String orchestrationMode;
    private String lifecycleLevel;
    private String riskLevel;
    private String status;
    private Integer importance;
    private Integer urgency;
    private Integer queuePriority;
    private Long ownerId;
    private String ownerPrincipalType;
    private LocalDateTime startAt;
    private Long currentVersionId;
    private Long latestRunId;
    private String acceptanceMode;
    private String acceptanceConfigJson;
    private String budgetJson;
    private String externalRefsJson;
    private String tagsJson;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private String delFlag;
    private String extraJson;
}
