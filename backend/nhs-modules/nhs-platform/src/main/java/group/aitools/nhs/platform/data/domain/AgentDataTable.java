package group.aitools.nhs.platform.data.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Synchronized table metadata with platform-managed presentation fields. */
@Data
@TableName("agent_data_table")
public class AgentDataTable {

    @TableId
    private Long id;
    private Long datasetId;
    private String tableKey;
    private String physicalSchema;
    private String physicalName;
    private String displayName;
    private String description;
    private String tableType;
    private String status;
    private String synonymsJson;
    private Boolean metadataPresent;
    private String metadataJson;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private String delFlag;
}
