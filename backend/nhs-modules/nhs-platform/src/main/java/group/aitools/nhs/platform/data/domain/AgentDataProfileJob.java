package group.aitools.nhs.platform.data.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Durable full or incremental metadata profiling job. */
@Data
@TableName("agent_data_profile_job")
public class AgentDataProfileJob {

    @TableId
    private Long id;
    private Long datasetId;
    private Long dataSourceId;
    private String mode;
    private String status;
    private String requestedTableIdsJson;
    private Integer datasetRevision;
    private Integer dataSourceRevision;
    private Integer totalTables;
    private Integer completedTables;
    private Integer failedTables;
    private BigDecimal progressPercent;
    private Long currentTableId;
    private Boolean cancelRequested;
    private Long resumeOfJobId;
    private String workerId;
    private LocalDateTime leaseUntil;
    private Integer attemptNo;
    private Integer maxAttempts;
    private Integer revisionNo;
    private String errorMessage;
    private Long requestedBy;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
    @TableField(exist = false)
    private Boolean recovered;
}
