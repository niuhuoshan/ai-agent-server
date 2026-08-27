package group.aitools.nhs.platform.data.web;

import group.aitools.nhs.platform.data.domain.AgentDataSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

/** Data source projection that never exposes the credential reference or value. */
public record DataSourceView(
    Long id,
    String sourceKey,
    String name,
    String dbType,
    String endpointUrl,
    String databaseName,
    boolean credentialConfigured,
    String status,
    Map<String, Object> config,
    Integer revisionNo,
    Integer connectionTimeoutMs,
    Integer statementTimeoutMs,
    Integer maxRows,
    Integer maxResultBytes,
    String lastTestStatus,
    LocalDateTime lastTestAt,
    String lastTestError,
    LocalDateTime lastMetadataSyncAt,
    String lastMetadataSyncError,
    LocalDateTime createTime,
    LocalDateTime updateTime
) {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    public static DataSourceView from(AgentDataSource source, JsonMapper jsonMapper) {
        Map<String, Object> config = source.getConfigJson() == null
            ? Map.of() : jsonMapper.readValue(source.getConfigJson(), MAP_TYPE);
        return new DataSourceView(
            source.getId(), source.getSourceKey(), source.getName(), source.getDbType(),
            source.getEndpointUrl(), source.getDatabaseName(),
            source.getCredentialRef() != null && !source.getCredentialRef().isBlank(),
            source.getStatus(), config, source.getRevisionNo(), source.getConnectionTimeoutMs(),
            source.getStatementTimeoutMs(), source.getMaxRows(), source.getMaxResultBytes(),
            source.getLastTestStatus(), source.getLastTestAt(), source.getLastTestError(),
            source.getLastMetadataSyncAt(), source.getLastMetadataSyncError(),
            source.getCreateTime(), source.getUpdateTime()
        );
    }
}
