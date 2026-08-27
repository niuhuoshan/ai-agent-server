package group.aitools.nhs.platform.data.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Synchronized column metadata and sensitivity classification. */
@Data
@TableName("agent_data_column")
public class AgentDataColumn {

    @TableId
    private Long id;
    private Long tableId;
    private String columnKey;
    private String physicalName;
    private String displayName;
    private String dataType;
    private String description;
    private Boolean isPrimary;
    private Boolean isSensitive;
    private String enumJson;
    private String synonymsJson;
    private String sampleValuesJson;
    private String status;
    private Boolean metadataPresent;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
