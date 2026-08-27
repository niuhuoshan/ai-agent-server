package group.aitools.nhs.platform.data.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Immutable table profile with versioned manual ignore decisions. */
@Data
@TableName("agent_data_table_profile")
public class AgentDataTableProfile {

    @TableId
    private Long id;
    private Long datasetId;
    private Long tableId;
    private Long jobId;
    private String sourceHash;
    private String tableType;
    private String term;
    private String description;
    private String ddlText;
    private Long rowCountEstimate;
    private Integer columnCount;
    private String columnsProfileJson;
    private String sampleDataJson;
    private Integer sampleRowCount;
    private Boolean sampleRedacted;
    private BigDecimal confidenceScore;
    private String confidenceReason;
    private String tagsJson;
    private String temporaryClassification;
    private Boolean ignored;
    private String ignoreDecision;
    private String profileJson;
    private Integer revisionNo;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
