package group.aitools.nhs.platform.data.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Governed JDBC data source configuration without resolved credentials. */
@Data
@TableName("agent_data_source")
public class AgentDataSource {

    @TableId
    private Long id;
    private String sourceKey;
    private String name;
    private String dbType;
    private String endpointUrl;
    private String databaseName;
    private String credentialRef;
    private Boolean readonly;
    private String status;
    private String configJson;
    private Integer revisionNo;
    private Integer connectionTimeoutMs;
    private Integer statementTimeoutMs;
    private Integer maxRows;
    private Integer maxResultBytes;
    private String lastTestStatus;
    private LocalDateTime lastTestAt;
    private String lastTestError;
    private LocalDateTime lastMetadataSyncAt;
    private String lastMetadataSyncError;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private String delFlag;
}
