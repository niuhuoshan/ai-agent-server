package group.aitools.nhs.platform.data.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Candidate table relationship inferred from synchronized column facts. */
@Data
@TableName("agent_data_profile_relation_recommendation")
public class AgentDataProfileRelationRecommendation {

    @TableId
    private Long id;
    private Long datasetId;
    private Long profileJobId;
    private Long sourceTableId;
    private Long sourceColumnId;
    private Long targetTableId;
    private Long targetColumnId;
    private BigDecimal confidenceScore;
    private String joinType;
    private String joinCondition;
    private String reason;
    private String status;
    private Long appliedRelationId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
