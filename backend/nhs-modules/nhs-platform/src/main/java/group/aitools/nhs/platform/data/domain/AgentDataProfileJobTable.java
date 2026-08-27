package group.aitools.nhs.platform.data.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Per-table execution fact belonging to a metadata profiling job. */
@Data
@TableName("agent_data_profile_job_table")
public class AgentDataProfileJobTable {

    @TableId
    private Long id;
    private Long jobId;
    private Long datasetId;
    private Long tableId;
    private Integer sequenceNo;
    private String sourceHash;
    private String status;
    private Integer attemptNo;
    private Long profileId;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
}
