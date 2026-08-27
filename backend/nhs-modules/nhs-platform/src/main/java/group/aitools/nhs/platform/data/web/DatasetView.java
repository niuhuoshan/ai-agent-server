package group.aitools.nhs.platform.data.web;

import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

/** Dataset control-plane projection. */
public record DatasetView(
    Long id,
    Long dataSourceId,
    String datasetKey,
    String name,
    String description,
    String status,
    List<String> schemaNames,
    Integer revisionNo,
    LocalDateTime lastSyncAt,
    String lastSyncError,
    Long ownerId,
    LocalDateTime createTime,
    LocalDateTime updateTime
) {

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {
    };

    public static DatasetView from(AgentDataDataset dataset, JsonMapper jsonMapper) {
        return new DatasetView(
            dataset.getId(), dataset.getDataSourceId(), dataset.getDatasetKey(), dataset.getName(),
            dataset.getDescription(), dataset.getStatus(),
            jsonMapper.readValue(dataset.getSchemaNamesJson(), LIST_TYPE), dataset.getRevisionNo(),
            dataset.getLastSyncAt(), dataset.getLastSyncError(), dataset.getOwnerId(),
            dataset.getCreateTime(), dataset.getUpdateTime()
        );
    }
}
