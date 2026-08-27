package group.aitools.nhs.platform.model.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体模型相关的领域对象。
 * Non-secret model registration used to build immutable runtime snapshots. */
@Data
@TableName("agent_model")
public class AgentModel {

    @TableId
    private Long id;
    private String modelKey;
    private String displayName;
    private String providerType;
    private String modelName;
    private String modelType;
    private String endpointUrl;
    private String credentialRef;
    private Integer contextSize;
    private Integer maxOutputTokens;
    private String reasoningConfigJson;
    private String status;
    private String capabilityJson;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private String delFlag;
    private String extraJson;
}
