package group.aitools.nhs.platform.data.service;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.MultiPartName;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.domain.AgentDataCatalogImportItem;
import group.aitools.nhs.platform.data.domain.AgentDataCatalogImportPreview;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataMetric;
import group.aitools.nhs.platform.data.domain.AgentDataRelation;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.data.mapper.DataCatalogImportMapper;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.mapper.DataGovernanceMapper;
import group.aitools.nhs.platform.data.persistence.row.MetadataChangeRow;
import group.aitools.nhs.platform.data.web.MetadataCatalogImportContracts.ApplyMetadataImportPreviewRequest;
import group.aitools.nhs.platform.data.web.MetadataCatalogImportContracts.CreateMetadataImportPreviewRequest;
import group.aitools.nhs.platform.data.web.MetadataCatalogImportContracts.MetadataImportApplyView;
import group.aitools.nhs.platform.data.web.MetadataCatalogImportContracts.MetadataImportDiagnosticView;
import group.aitools.nhs.platform.data.web.MetadataCatalogImportContracts.MetadataImportItemView;
import group.aitools.nhs.platform.data.web.MetadataCatalogImportContracts.MetadataImportPreviewView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static group.aitools.nhs.platform.data.service.MetadataYamlCodec.CatalogDocument;
import static group.aitools.nhs.platform.data.service.MetadataYamlCodec.ColumnDocument;
import static group.aitools.nhs.platform.data.service.MetadataYamlCodec.DatasetDocument;
import static group.aitools.nhs.platform.data.service.MetadataYamlCodec.MetricDocument;
import static group.aitools.nhs.platform.data.service.MetadataYamlCodec.RelationshipDocument;
import static group.aitools.nhs.platform.data.service.MetadataYamlCodec.TableDocument;

/** Canonical export plus durable, selective and atomic DDL/YAML catalog import. */
@Service
public class DataCatalogImportService {

    private static final int MAX_SOURCE_CHARS = 2_000_000;
    private static final int MAX_DIAGNOSTICS = 500;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<MetadataImportDiagnosticView>> DIAGNOSTIC_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final DataSourceCatalogService catalogService;
    private final DataCatalogMapper catalogMapper;
    private final DataCatalogImportMapper mapper;
    private final DataGovernanceMapper governanceMapper;
    private final MetadataDdlParser ddlParser;
    private final MetadataYamlCodec yamlCodec;
    private final JsonMapper jsonMapper;

    public DataCatalogImportService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        DataSourceCatalogService catalogService,
        DataCatalogMapper catalogMapper,
        DataCatalogImportMapper mapper,
        DataGovernanceMapper governanceMapper,
        MetadataDdlParser ddlParser,
        MetadataYamlCodec yamlCodec,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.catalogService = catalogService;
        this.catalogMapper = catalogMapper;
        this.mapper = mapper;
        this.governanceMapper = governanceMapper;
        this.ddlParser = ddlParser;
        this.yamlCodec = yamlCodec;
        this.jsonMapper = jsonMapper;
    }

    public ExportedMetadataYaml exportYaml(Long datasetId) {
        Access access = access(datasetId, "view");
        AgentDataDataset dataset = access.dataset();
        List<AgentDataTable> tables = catalogMapper.selectTables(datasetId);
        List<AgentDataColumn> columns = catalogMapper.selectColumns(datasetId);
        Map<Long, List<AgentDataColumn>> columnsByTable = columns.stream()
            .collect(Collectors.groupingBy(AgentDataColumn::getTableId));
        Map<Long, AgentDataTable> tablesById = tables.stream()
            .collect(Collectors.toMap(AgentDataTable::getId, Function.identity()));
        List<TableDocument> tableDocuments = tables.stream()
            .map(table -> tableDocument(table, columnsByTable.getOrDefault(table.getId(), List.of())))
            .toList();
        List<MetricDocument> metrics = governanceMapper.selectLatestMetrics(datasetId).stream()
            .map(this::metricDocument).toList();
        List<RelationshipDocument> relationships = relationshipDocuments(
            governanceMapper.selectRelationships(datasetId), tablesById
        );
        CatalogDocument document = new CatalogDocument(
            MetadataYamlCodec.DOCUMENT_VERSION,
            new DatasetDocument(dataset.getDatasetKey(), dataset.getName(), dataset.getDescription()),
            tableDocuments,
            metrics,
            relationships
        );
        String content = yamlCodec.write(document);
        return new ExportedMetadataYaml(
            "metadata-" + dataset.getDatasetKey() + ".yaml",
            content.getBytes(StandardCharsets.UTF_8)
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public MetadataImportPreviewView createPreview(
        Long datasetId,
        CreateMetadataImportPreviewRequest request
    ) {
        Access access = access(datasetId, "update");
        AgentDataDataset dataset = access.dataset();
        String content = request.content();
        if (content.length() > MAX_SOURCE_CHARS) {
            throw badRequest("元数据导入内容不能超过 " + MAX_SOURCE_CHARS + " 个字符");
        }
        String sourceType = request.format().strip().toLowerCase(Locale.ROOT);
        if (!Set.of("ddl", "yaml").contains(sourceType)) {
            throw badRequest("元数据导入格式仅支持 ddl 或 yaml");
        }
        CatalogDocument document;
        try {
            document = "ddl".equals(sourceType)
                ? ddlParser.parse(content, defaultSchema(dataset))
                : yamlCodec.parse(content);
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }
        if (document.tables().isEmpty() && document.metrics().isEmpty()
            && document.relationships().isEmpty()) {
            throw badRequest("元数据导入至少需要一张表、一个指标或一条关系");
        }
        requireAllowedSchemas(dataset, document);

        List<AgentDataTable> currentTables = catalogMapper.selectTables(datasetId);
        List<AgentDataColumn> currentColumns = catalogMapper.selectColumns(datasetId);
        Map<String, AgentDataTable> tableByName = tableByName(currentTables);
        Map<Long, List<AgentDataColumn>> columnsByTable = currentColumns.stream()
            .collect(Collectors.groupingBy(AgentDataColumn::getTableId));
        Map<String, AgentDataMetric> metricByKey = governanceMapper.selectLatestMetrics(datasetId).stream()
            .collect(Collectors.toMap(metric -> normalized(metric.getMetricKey()), Function.identity()));
        Map<Long, AgentDataTable> tableById = currentTables.stream()
            .collect(Collectors.toMap(AgentDataTable::getId, Function.identity()));
        Map<String, AgentDataRelation> relationByKey = relationshipByKey(
            governanceMapper.selectRelationships(datasetId), tableById
        );
        requireKnownRelationshipEndpoints(document, tableByName);

        List<MetadataImportDiagnosticView> diagnostics = diagnostics(dataset, sourceType, document, tableByName);
        LocalDateTime now = LocalDateTime.now();
        AgentDataCatalogImportPreview preview = new AgentDataCatalogImportPreview();
        preview.setId(idGenerator.nextId());
        preview.setDatasetId(datasetId);
        preview.setSourceType(sourceType);
        preview.setSourceHash(ContentHashing.sha256(content));
        preview.setStatus("draft");
        preview.setDatasetRevision(dataset.getRevisionNo());
        preview.setRevisionNo(1);
        preview.setTableCount(document.tables().size());
        preview.setColumnCount(document.tables().stream().mapToInt(table -> table.columns().size()).sum());
        preview.setDiagnosticsJson(jsonMapper.writeValueAsString(diagnostics));
        preview.setExpiresAt(now.plusMinutes(30));
        preview.setCreatedBy(access.principal().id());
        preview.setCreatedAt(now);
        if (mapper.insertPreview(preview) != 1) {
            throw conflict("元数据导入预览创建失败");
        }

        for (TableDocument table : document.tables()) {
            AgentDataTable current = tableByName.get(normalized(MetadataYamlCodec.qualifiedName(table)));
            TableDocument before = current == null ? null
                : tableDocument(current, columnsByTable.getOrDefault(current.getId(), List.of()));
            insertItem(preview.getId(), "table", MetadataYamlCodec.qualifiedName(table), before, table, now);
        }
        for (MetricDocument metric : document.metrics()) {
            AgentDataMetric current = metricByKey.get(normalized(metric.key()));
            insertItem(preview.getId(), "metric", metric.key(),
                current == null ? null : metricDocument(current), metric, now);
        }
        for (RelationshipDocument relationship : document.relationships()) {
            String resourceKey = relationshipResourceKey(relationship);
            AgentDataRelation current = relationByKey.get(resourceKey);
            insertItem(preview.getId(), "relationship", resourceKey,
                current == null ? null : relationshipDocument(current, tableById), relationship, now);
        }
        change(datasetId, preview.getId(), "create", null, Map.of(
            "previewId", preview.getId(),
            "sourceType", sourceType,
            "sourceHash", preview.getSourceHash(),
            "tableCount", preview.getTableCount(),
            "columnCount", preview.getColumnCount(),
            "datasetRevision", preview.getDatasetRevision()
        ), access.principal().id(), now);
        return previewView(preview, mapper.selectItems(preview.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public MetadataImportPreviewView preview(Long datasetId, Long previewId) {
        access(datasetId, "view");
        LocalDateTime now = LocalDateTime.now();
        mapper.expirePreview(datasetId, previewId, now);
        AgentDataCatalogImportPreview preview = mapper.selectPreview(datasetId, previewId);
        if (preview == null) {
            throw notFound("元数据导入预览不存在");
        }
        return previewView(preview, mapper.selectItems(previewId));
    }

    @Transactional(rollbackFor = Exception.class)
    public MetadataImportApplyView apply(
        Long datasetId,
        Long previewId,
        ApplyMetadataImportPreviewRequest request
    ) {
        Access access = access(datasetId, "update");
        LocalDateTime now = LocalDateTime.now();
        AgentDataCatalogImportPreview preview = mapper.selectPreviewForUpdate(datasetId, previewId);
        if (preview == null) {
            throw notFound("元数据导入预览不存在");
        }
        if (!"draft".equals(preview.getStatus()) || !preview.getExpiresAt().isAfter(now)) {
            throw conflict("元数据导入预览已应用或已过期");
        }
        if (!Objects.equals(request.revisionNo(), preview.getRevisionNo())) {
            throw conflict("元数据导入预览版本已变化，请刷新后重试");
        }
        AgentDataDataset dataset = mapper.selectDatasetForUpdate(datasetId);
        if (dataset == null || !Objects.equals(dataset.getRevisionNo(), preview.getDatasetRevision())) {
            throw conflict("数据集在预览生成后已变化，请重新生成预览");
        }

        List<AgentDataCatalogImportItem> allItems = mapper.selectItemsForUpdate(previewId);
        Map<Long, AgentDataCatalogImportItem> itemById = allItems.stream()
            .collect(Collectors.toMap(AgentDataCatalogImportItem::getId, Function.identity()));
        LinkedHashSet<Long> selectedIds = new LinkedHashSet<>(request.itemIds());
        if (selectedIds.size() != request.itemIds().size()
            || selectedIds.stream().anyMatch(id -> !itemById.containsKey(id))) {
            throw badRequest("元数据导入选择包含重复或未知预览项");
        }
        List<AgentDataCatalogImportItem> selected = allItems.stream()
            .filter(item -> selectedIds.contains(item.getId())).toList();
        if (selected.stream().anyMatch(item -> !"available".equals(item.getStatus()))) {
            throw conflict("元数据导入预览项已处理，请重新生成预览");
        }
        selected.forEach(this::verifyProposalHash);

        ApplyState state = lockApplyState(datasetId);
        for (AgentDataCatalogImportItem item : selected) {
            verifyCurrentState(item, state);
        }

        List<Long> appliedItemIds = new ArrayList<>();
        for (AgentDataCatalogImportItem item : selected) {
            Long resourceId = switch (item.getItemType()) {
                case "table" -> applyTable(datasetId, item, state, access.principal().id(), now);
                case "metric" -> applyMetric(datasetId, item, state, access.principal().id(), now);
                case "relationship" -> applyRelationship(datasetId, item, state, access.principal().id(), now);
                default -> throw new IllegalStateException("未知元数据导入项类型：" + item.getItemType());
            };
            if (mapper.markItemApplied(previewId, item.getId(), resourceId, now) != 1) {
                throw conflict("元数据导入预览项状态已变化");
            }
            appliedItemIds.add(item.getId());
        }
        List<Long> skippedItemIds = allItems.stream()
            .filter(item -> !selectedIds.contains(item.getId()) && "available".equals(item.getStatus()))
            .map(AgentDataCatalogImportItem::getId).toList();
        mapper.skipRemainingItems(previewId, now);
        if (mapper.advanceDatasetRevision(datasetId, preview.getDatasetRevision(), access.principal().id(), now) != 1) {
            throw conflict("数据集版本已变化，请重新生成预览");
        }
        if (mapper.completePreview(
            datasetId, previewId, preview.getRevisionNo(), access.principal().id(), now
        ) != 1) {
            throw conflict("元数据导入预览状态已变化");
        }
        change(datasetId, previewId, "update",
            Map.of("status", "draft", "revisionNo", preview.getRevisionNo()),
            Map.of(
                "status", "applied",
                "appliedItemIds", List.copyOf(appliedItemIds),
                "skippedItemIds", List.copyOf(skippedItemIds),
                "datasetRevision", preview.getDatasetRevision() + 1
            ), access.principal().id(), now);
        return new MetadataImportApplyView(
            previewId, "applied", preview.getDatasetRevision() + 1, preview.getRevisionNo() + 1,
            appliedItemIds, skippedItemIds, now
        );
    }

    private ApplyState lockApplyState(Long datasetId) {
        List<AgentDataTable> tables = new ArrayList<>(mapper.selectTablesForUpdate(datasetId));
        List<AgentDataColumn> columns = new ArrayList<>(mapper.selectColumnsForUpdate(datasetId));
        List<AgentDataMetric> metrics = new ArrayList<>(mapper.selectLatestMetricsForUpdate(datasetId));
        List<AgentDataRelation> relationships = new ArrayList<>(mapper.selectRelationshipsForUpdate(datasetId));
        Map<String, AgentDataTable> tableByName = tableByName(tables);
        Map<Long, AgentDataTable> tableById = tables.stream()
            .collect(Collectors.toMap(
                AgentDataTable::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new
            ));
        Map<Long, List<AgentDataColumn>> columnsByTable = columns.stream()
            .collect(Collectors.groupingBy(
                AgentDataColumn::getTableId, LinkedHashMap::new,
                Collectors.toCollection(ArrayList::new)
            ));
        Map<String, AgentDataMetric> metricByKey = metrics.stream()
            .collect(Collectors.toMap(metric -> normalized(metric.getMetricKey()), Function.identity(),
                (left, right) -> left, LinkedHashMap::new));
        Map<String, AgentDataRelation> relationshipByKey = relationshipByKey(relationships, tableById);
        return new ApplyState(
            tables, columns, metrics, relationships, tableByName, tableById,
            columnsByTable, metricByKey, relationshipByKey
        );
    }

    private void verifyCurrentState(AgentDataCatalogImportItem item, ApplyState state) {
        Object current = switch (item.getItemType()) {
            case "table" -> {
                AgentDataTable table = state.tableByName().get(normalized(item.getResourceKey()));
                yield table == null ? null : tableDocument(
                    table, state.columnsByTable().getOrDefault(table.getId(), List.of())
                );
            }
            case "metric" -> {
                AgentDataMetric metric = state.metricByKey().get(normalized(item.getResourceKey()));
                yield metric == null ? null : metricDocument(metric);
            }
            case "relationship" -> {
                AgentDataRelation relationship = state.relationshipByKey().get(item.getResourceKey());
                yield relationship == null ? null : relationshipDocument(relationship, state.tableById());
            }
            default -> throw new IllegalStateException("未知元数据导入项类型：" + item.getItemType());
        };
        String currentHash = current == null ? null : hash(current);
        if (!Objects.equals(currentHash, item.getCurrentHash())) {
            throw conflict("元数据资源已变化，请重新生成导入预览：" + item.getResourceKey());
        }
        String expectedAction = current == null ? "create" : "update";
        if (!expectedAction.equals(item.getAction())) {
            throw conflict("元数据导入动作与当前资源状态不一致");
        }
    }

    private Long applyTable(
        Long datasetId,
        AgentDataCatalogImportItem item,
        ApplyState state,
        Long actorId,
        LocalDateTime now
    ) {
        TableDocument proposal = jsonMapper.readValue(item.getProposedJson(), TableDocument.class);
        String key = normalized(MetadataYamlCodec.qualifiedName(proposal));
        AgentDataTable table = state.tableByName().get(key);
        if (table == null) {
            table = new AgentDataTable();
            table.setId(idGenerator.nextId());
            table.setDatasetId(datasetId);
            table.setTableKey("table." + ContentHashing.sha256(key).substring(0, 24));
            table.setPhysicalSchema(proposal.schema());
            table.setPhysicalName(proposal.name());
            table.setDisplayName(proposal.displayName());
            table.setDescription(proposal.description());
            table.setTableType(proposal.type());
            table.setStatus("inactive");
            table.setSynonymsJson(jsonMapper.writeValueAsString(proposal.synonyms()));
            table.setMetadataPresent(false);
            table.setMetadataJson(jsonMapper.writeValueAsString(Map.of("declaredBy", "metadata_import")));
            table.setCreateBy(actorId);
            table.setCreateTime(now);
            table.setDelFlag("0");
            if (mapper.insertDeclaredTable(table) != 1) {
                throw conflict("声明数据表创建失败：" + item.getResourceKey());
            }
            state.tables().add(table);
            state.tableByName().put(key, table);
            state.tableById().put(table.getId(), table);
            state.columnsByTable().put(table.getId(), new ArrayList<>());
        } else {
            table.setDisplayName(proposal.displayName());
            table.setDescription(proposal.description());
            table.setTableType(proposal.type());
            table.setStatus(Boolean.TRUE.equals(table.getMetadataPresent()) ? proposal.status() : "inactive");
            table.setSynonymsJson(jsonMapper.writeValueAsString(proposal.synonyms()));
            table.setUpdateBy(actorId);
            table.setUpdateTime(now);
            if (mapper.updateImportedTable(table) != 1) {
                throw conflict("数据表语义信息更新失败：" + item.getResourceKey());
            }
        }
        applyColumns(table, proposal.columns(), state, now);
        return table.getId();
    }

    private void applyColumns(
        AgentDataTable table,
        List<ColumnDocument> proposals,
        ApplyState state,
        LocalDateTime now
    ) {
        List<AgentDataColumn> tableColumns = state.columnsByTable()
            .computeIfAbsent(table.getId(), ignored -> new ArrayList<>());
        Map<String, AgentDataColumn> byName = tableColumns.stream()
            .collect(Collectors.toMap(column -> normalized(column.getPhysicalName()), Function.identity(),
                (left, right) -> left, LinkedHashMap::new));
        for (ColumnDocument proposal : proposals) {
            String key = normalized(proposal.name());
            AgentDataColumn column = byName.get(key);
            if (column == null) {
                column = new AgentDataColumn();
                column.setId(idGenerator.nextId());
                column.setTableId(table.getId());
                column.setColumnKey("column." + ContentHashing.sha256(key).substring(0, 24));
                column.setPhysicalName(proposal.name());
                column.setDisplayName(proposal.displayName());
                column.setDataType(proposal.type());
                column.setDescription(proposal.description());
                column.setIsPrimary(proposal.primary());
                column.setIsSensitive(proposal.sensitive());
                column.setEnumJson(jsonMapper.writeValueAsString(proposal.enums()));
                column.setSynonymsJson(jsonMapper.writeValueAsString(proposal.synonyms()));
                column.setSampleValuesJson("[]");
                column.setStatus("inactive");
                column.setMetadataPresent(false);
                column.setCreatedAt(now);
                if (mapper.insertDeclaredColumn(column) != 1) {
                    throw conflict("声明数据列创建失败：" + proposal.name());
                }
                tableColumns.add(column);
                state.columns().add(column);
                byName.put(key, column);
            } else {
                column.setDisplayName(proposal.displayName());
                column.setDescription(proposal.description());
                column.setDataType(proposal.type());
                column.setIsPrimary(proposal.primary());
                column.setIsSensitive(proposal.sensitive());
                column.setEnumJson(jsonMapper.writeValueAsString(proposal.enums()));
                column.setSynonymsJson(jsonMapper.writeValueAsString(proposal.synonyms()));
                column.setStatus(Boolean.TRUE.equals(column.getMetadataPresent()) ? proposal.status() : "inactive");
                column.setUpdatedAt(now);
                if (mapper.updateImportedColumn(column) != 1) {
                    throw conflict("数据列语义信息更新失败：" + proposal.name());
                }
            }
        }
    }

    private Long applyMetric(
        Long datasetId,
        AgentDataCatalogImportItem item,
        ApplyState state,
        Long actorId,
        LocalDateTime now
    ) {
        MetricDocument proposal = jsonMapper.readValue(item.getProposedJson(), MetricDocument.class);
        AgentDataMetric current = state.metricByKey().get(normalized(proposal.key()));
        AgentDataMetric metric = new AgentDataMetric();
        metric.setId(idGenerator.nextId());
        metric.setDatasetId(datasetId);
        metric.setMetricKey(proposal.key());
        metric.setName(proposal.name());
        metric.setDescription(proposal.description());
        metric.setCalculationLogic(proposal.calculationLogic());
        metric.setUnit(proposal.unit());
        metric.setStatus(proposal.status());
        metric.setVersionNo(current == null ? 1 : current.getVersionNo() + 1);
        metric.setCreatedBy(actorId);
        metric.setCreatedAt(now);
        metric.setUpdatedAt(now);
        if (governanceMapper.insertMetric(metric) != 1) {
            throw conflict("指标导入失败：" + proposal.key());
        }
        state.metrics().add(metric);
        state.metricByKey().put(normalized(proposal.key()), metric);
        return metric.getId();
    }

    private Long applyRelationship(
        Long datasetId,
        AgentDataCatalogImportItem item,
        ApplyState state,
        Long actorId,
        LocalDateTime now
    ) {
        RelationshipDocument proposal = jsonMapper.readValue(item.getProposedJson(), RelationshipDocument.class);
        AgentDataTable source = state.tableByName().get(normalized(proposal.sourceTable()));
        AgentDataTable target = state.tableByName().get(normalized(proposal.targetTable()));
        if (source == null || target == null) {
            throw conflict("关系端点尚未导入，请同时选择对应数据表：" + item.getResourceKey());
        }
        AgentDataRelation relation = state.relationshipByKey().get(item.getResourceKey());
        boolean active = "active".equals(proposal.status())
            && validLiveRelationship(source, target, proposal.joinCondition(), state.columnsByTable())
            && governanceMapper.countActiveRelationship(
                datasetId, source.getId(), target.getId(), relation == null ? null : relation.getId()
            ) == 0;
        if (relation == null) {
            relation = new AgentDataRelation();
            relation.setId(idGenerator.nextId());
            relation.setDatasetId(datasetId);
            relation.setSourceTableId(source.getId());
            relation.setTargetTableId(target.getId());
            relation.setJoinType(proposal.joinType());
            relation.setJoinCondition(proposal.joinCondition());
            relation.setDescription(proposal.description());
            relation.setStatus(active ? "active" : "inactive");
            relation.setRevisionNo(1);
            relation.setCreatedBy(actorId);
            relation.setCreatedAt(now);
            relation.setUpdatedBy(actorId);
            relation.setUpdatedAt(now);
            if (governanceMapper.insertRelationship(relation) != 1) {
                throw conflict("数据关系导入失败：" + item.getResourceKey());
            }
            state.relationships().add(relation);
            state.relationshipByKey().put(item.getResourceKey(), relation);
        } else {
            relation.setSourceTableId(source.getId());
            relation.setTargetTableId(target.getId());
            relation.setJoinType(proposal.joinType());
            relation.setJoinCondition(proposal.joinCondition());
            relation.setDescription(proposal.description());
            relation.setStatus(active ? "active" : "inactive");
            relation.setUpdatedBy(actorId);
            relation.setUpdatedAt(now);
            if (governanceMapper.updateRelationship(relation) != 1) {
                throw conflict("数据关系已变化，请重新生成导入预览");
            }
            relation.setRevisionNo(relation.getRevisionNo() + 1);
        }
        return relation.getId();
    }

    private boolean validLiveRelationship(
        AgentDataTable source,
        AgentDataTable target,
        String joinCondition,
        Map<Long, List<AgentDataColumn>> columnsByTable
    ) {
        if (!live(source) || !live(target) || source.getId().equals(target.getId())) {
            return false;
        }
        try {
            Expression expression = CCJSqlParserUtil.parseCondExpression(joinCondition);
            List<ColumnPair> pairs = joinPairs(expression);
            if (pairs.isEmpty()) {
                return false;
            }
            for (ColumnPair pair : pairs) {
                AgentDataTable leftTable = referencedTable(pair.left(), source, target);
                AgentDataTable rightTable = referencedTable(pair.right(), source, target);
                if (leftTable == null || rightTable == null || leftTable.getId().equals(rightTable.getId())
                    || !liveColumn(pair.left(), leftTable, columnsByTable)
                    || !liveColumn(pair.right(), rightTable, columnsByTable)) {
                    return false;
                }
            }
            return true;
        } catch (JSQLParserException exception) {
            return false;
        }
    }

    private List<ColumnPair> joinPairs(Expression expression) {
        if (expression instanceof EqualsTo equals
            && equals.getLeftExpression() instanceof Column left
            && equals.getRightExpression() instanceof Column right) {
            return List.of(new ColumnPair(left, right));
        }
        if (expression instanceof AndExpression and) {
            List<ColumnPair> result = new ArrayList<>();
            result.addAll(joinPairs(and.getLeftExpression()));
            result.addAll(joinPairs(and.getRightExpression()));
            return result;
        }
        return List.of();
    }

    private AgentDataTable referencedTable(Column column, AgentDataTable source, AgentDataTable target) {
        if (column.getTable() == null) {
            return null;
        }
        String qualifier = MultiPartName.unquote(column.getTable().getUnquotedName());
        if (matches(source, qualifier)) {
            return source;
        }
        return matches(target, qualifier) ? target : null;
    }

    private boolean liveColumn(
        Column reference,
        AgentDataTable table,
        Map<Long, List<AgentDataColumn>> columnsByTable
    ) {
        String name = MultiPartName.unquote(reference.getUnquotedColumnName());
        return columnsByTable.getOrDefault(table.getId(), List.of()).stream()
            .anyMatch(column -> column.getPhysicalName().equalsIgnoreCase(name)
                && "active".equals(column.getStatus())
                && Boolean.TRUE.equals(column.getMetadataPresent()));
    }

    private boolean matches(AgentDataTable table, String qualifier) {
        return qualifier != null && (table.getPhysicalName().equalsIgnoreCase(qualifier)
            || table.getTableKey().equalsIgnoreCase(qualifier));
    }

    private boolean live(AgentDataTable table) {
        return "active".equals(table.getStatus()) && Boolean.TRUE.equals(table.getMetadataPresent());
    }

    private void insertItem(
        Long previewId,
        String itemType,
        String resourceKey,
        Object before,
        Object proposal,
        LocalDateTime now
    ) {
        String proposedJson = canonicalJson(proposal);
        AgentDataCatalogImportItem item = new AgentDataCatalogImportItem();
        item.setId(idGenerator.nextId());
        item.setPreviewId(previewId);
        item.setItemType(itemType);
        item.setResourceKey(resourceKey);
        item.setAction(before == null ? "create" : "update");
        item.setCurrentHash(before == null ? null : hash(before));
        item.setContentHash(ContentHashing.sha256(proposedJson));
        item.setProposedJson(proposedJson);
        item.setStatus("available");
        item.setCreatedAt(now);
        if (mapper.insertItem(item) != 1) {
            throw conflict("元数据导入预览项创建失败：" + resourceKey);
        }
    }

    private void verifyProposalHash(AgentDataCatalogImportItem item) {
        String current = ContentHashing.sha256(canonicalJson(item.getProposedJson()));
        if (!current.equals(item.getContentHash())) {
            throw conflict("元数据导入预览内容校验失败，请重新生成预览");
        }
    }

    private List<MetadataImportDiagnosticView> diagnostics(
        AgentDataDataset dataset,
        String sourceType,
        CatalogDocument document,
        Map<String, AgentDataTable> currentTables
    ) {
        List<MetadataImportDiagnosticView> result = new ArrayList<>();
        if (document.dataset() != null
            && !dataset.getDatasetKey().equalsIgnoreCase(document.dataset().key())) {
            result.add(new MetadataImportDiagnosticView(
                "warning", "dataset_key_mismatch",
                "YAML 中的数据集标识与目标数据集不同，导入只应用目录资源", document.dataset().key()
            ));
        }
        if ("ddl".equals(sourceType)) {
            result.add(new MetadataImportDiagnosticView(
                "info", "ddl_semantics_defaulted",
                "DDL 只提供物理结构，中文名、描述、同义词、指标和关系需在预览后补充", null
            ));
        }
        for (TableDocument table : document.tables()) {
            String name = MetadataYamlCodec.qualifiedName(table);
            AgentDataTable current = currentTables.get(normalized(name));
            if (current == null) {
                addDiagnostic(result, new MetadataImportDiagnosticView(
                    "warning", "declared_table",
                    "目标库尚未同步到该表，将以不可查询的声明状态导入", name
                ));
            }
        }
        for (RelationshipDocument relationship : document.relationships()) {
            AgentDataTable source = currentTables.get(normalized(relationship.sourceTable()));
            AgentDataTable target = currentTables.get(normalized(relationship.targetTable()));
            if ("active".equals(relationship.status())
                && (!relationshipConditionSyntaxValid(relationship.joinCondition())
                    || source == null || target == null || !live(source) || !live(target))) {
                addDiagnostic(result, new MetadataImportDiagnosticView(
                    "warning", "relationship_will_be_inactive",
                    "关系端点或等值连接条件尚不可用于查询，将以停用状态保存",
                    relationshipResourceKey(relationship)
                ));
            }
        }
        return List.copyOf(result);
    }

    private boolean relationshipConditionSyntaxValid(String joinCondition) {
        try {
            Expression expression = CCJSqlParserUtil.parseCondExpression(joinCondition);
            List<ColumnPair> pairs = joinPairs(expression);
            return !pairs.isEmpty() && pairs.stream()
                .allMatch(pair -> pair.left().getTable() != null && pair.right().getTable() != null);
        } catch (JSQLParserException exception) {
            return false;
        }
    }

    private void requireKnownRelationshipEndpoints(
        CatalogDocument document,
        Map<String, AgentDataTable> currentTables
    ) {
        Set<String> known = new HashSet<>(currentTables.keySet());
        document.tables().stream()
            .map(MetadataYamlCodec::qualifiedName)
            .map(this::normalized)
            .forEach(known::add);
        for (RelationshipDocument relationship : document.relationships()) {
            if (!known.contains(normalized(relationship.sourceTable()))
                || !known.contains(normalized(relationship.targetTable()))) {
                throw badRequest("数据关系引用了未声明的数据表："
                    + relationship.sourceTable() + " -> " + relationship.targetTable());
            }
        }
    }

    private void requireAllowedSchemas(AgentDataDataset dataset, CatalogDocument document) {
        Set<String> allowed = stringList(dataset.getSchemaNamesJson()).stream()
            .map(this::normalized)
            .collect(Collectors.toUnmodifiableSet());
        if (allowed.isEmpty()) {
            throw conflict("数据集没有可用的 Schema 白名单");
        }
        for (TableDocument table : document.tables()) {
            if (!allowed.contains(normalized(table.schema()))) {
                throw badRequest("数据表 Schema 不在数据集白名单内："
                    + MetadataYamlCodec.qualifiedName(table));
            }
        }
        for (RelationshipDocument relationship : document.relationships()) {
            requireAllowedRelationshipEndpointSchema(allowed, relationship.sourceTable());
            requireAllowedRelationshipEndpointSchema(allowed, relationship.targetTable());
        }
    }

    private void requireAllowedRelationshipEndpointSchema(Set<String> allowed, String endpoint) {
        int separator = endpoint.lastIndexOf('.');
        String schema = separator <= 0 ? "" : endpoint.substring(0, separator);
        if (!allowed.contains(normalized(schema))) {
            throw badRequest("数据关系端点 Schema 不在数据集白名单内：" + endpoint);
        }
    }

    private void addDiagnostic(
        List<MetadataImportDiagnosticView> diagnostics,
        MetadataImportDiagnosticView diagnostic
    ) {
        if (diagnostics.size() < MAX_DIAGNOSTICS) {
            diagnostics.add(diagnostic);
        }
    }

    private MetadataImportPreviewView previewView(
        AgentDataCatalogImportPreview preview,
        List<AgentDataCatalogImportItem> items
    ) {
        List<MetadataImportDiagnosticView> diagnostics = jsonMapper.readValue(
            preview.getDiagnosticsJson(), DIAGNOSTIC_LIST
        );
        return new MetadataImportPreviewView(
            preview.getId(), preview.getDatasetId(), preview.getSourceType(), preview.getStatus(),
            preview.getDatasetRevision(), preview.getRevisionNo(), preview.getTableCount(),
            preview.getColumnCount(), diagnostics, preview.getExpiresAt(), preview.getCreatedBy(),
            preview.getCreatedAt(), preview.getAppliedBy(), preview.getAppliedAt(),
            items.stream().map(this::itemView).toList()
        );
    }

    private MetadataImportItemView itemView(AgentDataCatalogImportItem item) {
        return new MetadataImportItemView(
            item.getId(), item.getItemType(), item.getResourceKey(), item.getAction(), item.getStatus(),
            item.getCurrentHash(), item.getContentHash(), jsonMapper.readValue(item.getProposedJson(), MAP_TYPE),
            item.getAppliedResourceId(), item.getErrorMessage()
        );
    }

    private TableDocument tableDocument(AgentDataTable table, List<AgentDataColumn> columns) {
        List<ColumnDocument> columnDocuments = columns.stream()
            .map(this::columnDocument)
            .sorted(Comparator.comparing(ColumnDocument::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
        return new TableDocument(
            table.getPhysicalSchema(), table.getPhysicalName(), table.getDisplayName(), table.getDescription(),
            table.getTableType(), table.getStatus(), stringList(table.getSynonymsJson()), columnDocuments
        );
    }

    private ColumnDocument columnDocument(AgentDataColumn column) {
        return new ColumnDocument(
            column.getPhysicalName(), column.getDataType(), column.getDisplayName(), column.getDescription(),
            column.getIsPrimary(), column.getIsSensitive(), column.getStatus(),
            stringList(column.getEnumJson()), stringList(column.getSynonymsJson())
        );
    }

    private MetricDocument metricDocument(AgentDataMetric metric) {
        return new MetricDocument(
            metric.getMetricKey(), metric.getName(), metric.getDescription(),
            metric.getCalculationLogic(), metric.getUnit(), metric.getStatus()
        );
    }

    private RelationshipDocument relationshipDocument(
        AgentDataRelation relation,
        Map<Long, AgentDataTable> tables
    ) {
        AgentDataTable source = tables.get(relation.getSourceTableId());
        AgentDataTable target = tables.get(relation.getTargetTableId());
        if (source == null || target == null) {
            return null;
        }
        return new RelationshipDocument(
            qualified(source), qualified(target), relation.getJoinType(), relation.getJoinCondition(),
            relation.getDescription(), relation.getStatus()
        );
    }

    private Map<String, AgentDataRelation> relationshipByKey(
        List<AgentDataRelation> relationships,
        Map<Long, AgentDataTable> tables
    ) {
        Map<String, AgentDataRelation> result = new LinkedHashMap<>();
        for (AgentDataRelation relation : relationships) {
            RelationshipDocument document = relationshipDocument(relation, tables);
            if (document != null) {
                result.putIfAbsent(relationshipResourceKey(document), relation);
            }
        }
        return result;
    }

    private List<RelationshipDocument> relationshipDocuments(
        List<AgentDataRelation> relationships,
        Map<Long, AgentDataTable> tables
    ) {
        Map<String, RelationshipDocument> result = new LinkedHashMap<>();
        for (AgentDataRelation relation : relationships) {
            RelationshipDocument document = relationshipDocument(relation, tables);
            if (document != null) {
                result.putIfAbsent(relationshipResourceKey(document), document);
            }
        }
        return List.copyOf(result.values());
    }

    private String relationshipResourceKey(RelationshipDocument relationship) {
        return "relationship." + ContentHashing.sha256(
            normalized(MetadataYamlCodec.relationshipKey(relationship))
        );
    }

    private Map<String, AgentDataTable> tableByName(List<AgentDataTable> tables) {
        return tables.stream().collect(Collectors.toMap(
            table -> normalized(qualified(table)), Function.identity(),
            (left, right) -> left, LinkedHashMap::new
        ));
    }

    private String defaultSchema(AgentDataDataset dataset) {
        List<String> schemas = stringList(dataset.getSchemaNamesJson());
        return schemas.isEmpty() ? "public" : schemas.getFirst();
    }

    private List<String> stringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<String> result = jsonMapper.readValue(json, STRING_LIST);
        return result == null ? List.of() : List.copyOf(result);
    }

    private String qualified(AgentDataTable table) {
        return table.getPhysicalSchema() + "." + table.getPhysicalName();
    }

    private String normalized(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private String hash(Object value) {
        return ContentHashing.sha256(canonicalJson(value));
    }

    private String canonicalJson(Object value) {
        Object source = value instanceof String text
            ? jsonMapper.readValue(text, Object.class)
            : jsonMapper.readValue(jsonMapper.writeValueAsString(value), Object.class);
        return jsonMapper.writeValueAsString(canonicalValue(source));
    }

    private Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalValue(item)));
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::canonicalValue).toList();
        }
        return value;
    }

    private void change(
        Long datasetId,
        Long resourceId,
        String action,
        Object before,
        Object after,
        Long actorId,
        LocalDateTime now
    ) {
        String beforeJson = before == null ? null : canonicalJson(before);
        String afterJson = after == null ? null : canonicalJson(after);
        MetadataChangeRow row = new MetadataChangeRow();
        row.setId(idGenerator.nextId());
        row.setDatasetId(datasetId);
        row.setResourceType("metadata_import");
        row.setResourceId(resourceId);
        row.setAction(action);
        row.setBeforeJson(beforeJson);
        row.setAfterJson(afterJson);
        row.setBeforeHash(beforeJson == null ? null : ContentHashing.sha256(beforeJson));
        row.setAfterHash(afterJson == null ? null : ContentHashing.sha256(afterJson));
        row.setActorId(actorId);
        row.setCreatedAt(now);
        if (governanceMapper.insertChange(row) != 1) {
            throw conflict("元数据导入审计记录写入失败");
        }
    }

    private Access access(Long datasetId, String action) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentDataDataset dataset = catalogService.requireDataset(datasetId);
        authorizationEnforcer.requireAllowed(principal, catalogService.datasetContext(dataset, action));
        return new Access(principal, dataset);
    }

    private ServiceException badRequest(String message) {
        return new ServiceException(
            message == null || message.isBlank() ? "元数据导入请求无效" : message,
            HttpStatus.BAD_REQUEST
        );
    }

    private ServiceException notFound(String message) {
        return new ServiceException(message, HttpStatus.NOT_FOUND);
    }

    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    public record ExportedMetadataYaml(String fileName, byte[] content) {
        public ExportedMetadataYaml {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    private record Access(CurrentPrincipal principal, AgentDataDataset dataset) {
    }

    private record ApplyState(
        List<AgentDataTable> tables,
        List<AgentDataColumn> columns,
        List<AgentDataMetric> metrics,
        List<AgentDataRelation> relationships,
        Map<String, AgentDataTable> tableByName,
        Map<Long, AgentDataTable> tableById,
        Map<Long, List<AgentDataColumn>> columnsByTable,
        Map<String, AgentDataMetric> metricByKey,
        Map<String, AgentDataRelation> relationshipByKey
    ) {
    }

    private record ColumnPair(Column left, Column right) {
    }
}
