package group.aitools.nhs.platform.data.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Auditable query fact; result rows and credentials are deliberately not persisted here. */
@Data
@TableName("agent_data_query")
public class AgentDataQuery {

    @TableId
    private Long id;
    private Long taskId;
    private Long runId;
    private Long conversationId;
    private String traceId;
    private Long dataSourceId;
    private Long datasetId;
    private Integer dataSourceRevision;
    private Integer datasetRevision;
    private String userQuery;
    private String sqlPlanJson;
    private String sqlText;
    private String sqlHash;
    private String permissionSummaryJson;
    private Long rowCount;
    private Long resultBytes;
    private Boolean resultTruncated;
    private String status;
    private String errorSummary;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
}
