package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Applies a complete metadata snapshot while preserving labels and sensitivity decisions. */
@Service
public class DataMetadataPersistenceService {

    private final PlatformIdGenerator idGenerator;
    private final DataCatalogMapper mapper;
    private final JsonMapper jsonMapper;

    public DataMetadataPersistenceService(
        PlatformIdGenerator idGenerator,
        DataCatalogMapper mapper,
        JsonMapper jsonMapper
    ) {
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void applyCurrentSnapshot(
        Long datasetId,
        Integer datasetRevision,
        Long sourceId,
        Integer sourceRevision,
        List<DiscoveredTable> discovered,
        Long actorId,
        LocalDateTime now
    ) {
        AgentDataDataset currentDataset = mapper.lockDatasetForMetadataApply(datasetId);
        if (currentDataset == null
            || !java.util.Objects.equals(datasetRevision, currentDataset.getRevisionNo())
            || !"syncing".equals(currentDataset.getStatus())
            || !sourceId.equals(currentDataset.getDataSourceId())) {
            throw conflict("数据集配置已变化，本次元数据同步结果已丢弃");
        }
        AgentDataSource currentSource = mapper.lockSourceForMetadataApply(sourceId);
        if (currentSource == null
            || !java.util.Objects.equals(sourceRevision, currentSource.getRevisionNo())
            || !"active".equals(currentSource.getStatus())) {
            throw conflict("数据源配置已变化，本次元数据同步结果已丢弃");
        }
        applyRows(datasetId, discovered, actorId, now);
        if (mapper.finishDatasetSync(
            datasetId, datasetRevision, "active", null, actorId, now
        ) != 1) {
            throw conflict("数据集同步状态已变化，本次元数据同步结果已回滚");
        }
        if (mapper.recordSourceMetadataSync(
            sourceId, sourceRevision, null, actorId, now
        ) != 1) {
            throw conflict("数据源同步状态已变化，本次元数据同步结果已回滚");
        }
    }

    private void applyRows(Long datasetId, List<DiscoveredTable> discovered, Long actorId, LocalDateTime now) {
        Map<String, AgentDataTable> existingTables = new HashMap<>();
        for (AgentDataTable table : mapper.selectTables(datasetId)) {
            existingTables.put(key(table.getPhysicalSchema(), table.getPhysicalName()), table);
        }
        Map<Long, Map<String, AgentDataColumn>> existingColumns = new HashMap<>();
        for (AgentDataColumn column : mapper.selectColumns(datasetId)) {
            existingColumns.computeIfAbsent(column.getTableId(), ignored -> new HashMap<>())
                .put(column.getPhysicalName(), column);
        }

        List<Long> activeTableIds = new ArrayList<>();
        for (DiscoveredTable item : discovered) {
            AgentDataTable table = existingTables.get(key(item.schemaName(), item.tableName()));
            if (table == null) {
                table = new AgentDataTable();
                table.setId(idGenerator.nextId());
                table.setDatasetId(datasetId);
                table.setTableKey("table." + ContentHashing.sha256(key(item.schemaName(), item.tableName())).substring(0, 24));
                table.setPhysicalSchema(item.schemaName());
                table.setPhysicalName(item.tableName());
                table.setDisplayName(item.tableName());
                table.setDescription(null);
                table.setTableType(item.tableType());
                table.setStatus("active");
                table.setMetadataPresent(true);
                table.setMetadataJson(tableMetadata(item));
                table.setCreateBy(actorId);
                table.setCreateTime(now);
                table.setDelFlag("0");
                mapper.insertTable(table);
            } else {
                table.setTableType(item.tableType());
                table.setMetadataJson(tableMetadata(item));
                table.setUpdateBy(actorId);
                table.setUpdateTime(now);
                mapper.refreshTable(table);
            }
            activeTableIds.add(table.getId());
            applyColumns(table, item.columns(), existingColumns.getOrDefault(table.getId(), Map.of()), now);
        }
        mapper.deactivateMissingTables(datasetId, activeTableIds, actorId, now);
    }

    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    private void applyColumns(
        AgentDataTable table,
        List<DiscoveredColumn> discovered,
        Map<String, AgentDataColumn> existing,
        LocalDateTime now
    ) {
        List<Long> activeColumnIds = new ArrayList<>();
        for (DiscoveredColumn item : discovered) {
            AgentDataColumn column = existing.get(item.columnName());
            if (column == null) {
                column = new AgentDataColumn();
                column.setId(idGenerator.nextId());
                column.setTableId(table.getId());
                column.setColumnKey("column." + ContentHashing.sha256(item.columnName()).substring(0, 24));
                column.setPhysicalName(item.columnName());
                column.setDisplayName(item.columnName());
                column.setDataType(item.dataType());
                column.setDescription(null);
                column.setIsPrimary(item.primary());
                column.setIsSensitive(false);
                column.setStatus("active");
                column.setMetadataPresent(true);
                column.setCreatedAt(now);
                mapper.insertColumn(column);
            } else {
                column.setDataType(item.dataType());
                column.setIsPrimary(item.primary());
                column.setUpdatedAt(now);
                mapper.refreshColumn(column);
            }
            activeColumnIds.add(column.getId());
        }
        mapper.deactivateMissingColumns(table.getId(), activeColumnIds, now);
    }

    private String tableMetadata(DiscoveredTable table) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schema", table.schemaName());
        metadata.put("tableType", table.tableType());
        return jsonMapper.writeValueAsString(metadata);
    }

    private String key(String schema, String table) {
        return schema + '\u0000' + table;
    }

    public record DiscoveredTable(
        String schemaName,
        String tableName,
        String tableType,
        List<DiscoveredColumn> columns
    ) {

        public DiscoveredTable {
            columns = List.copyOf(columns);
        }
    }

    public record DiscoveredColumn(
        String columnName,
        String dataType,
        int ordinalPosition,
        boolean primary
    ) {
    }
}
