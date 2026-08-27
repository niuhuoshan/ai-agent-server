package group.aitools.nhs.platform.data.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Governed set of synchronized database tables. */
@Data
@TableName("agent_data_dataset")
public class AgentDataDataset {

    @TableId
    private Long id;
    private Long dataSourceId;
    private String datasetKey;
    private String name;
    private String description;
    private String status;
    private Boolean enableRowPolicy;
    private String rowPolicyJson;
    private String schemaNamesJson;
    private Integer revisionNo;
    private LocalDateTime lastSyncAt;
    private String lastSyncError;
    private Long ownerId;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private String delFlag;
}
