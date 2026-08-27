package group.aitools.nhs.platform.data.service;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataMetric;
import group.aitools.nhs.platform.data.domain.AgentDataRelation;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.mapper.DataGovernanceMapper;
import group.aitools.nhs.platform.data.persistence.row.MetadataChangeRow;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.CreateMetricRequest;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.CreateRelationshipRequest;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.MetadataChangeView;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.MetricView;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.RelationshipView;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.RowPolicyRule;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.RowPolicyView;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.UpdateMetricRequest;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.UpdateRelationshipRequest;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.UpdateRowPolicyRequest;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Versioned metadata governance with dataset authorization and durable before/after facts. */
@Service
public class DataGovernanceService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<PolicyDocument> POLICY_TYPE = new TypeReference<>() {
    };
    private static final Set<String> NUMERIC_TYPES = Set.of(
        "smallint", "integer", "int", "int2", "int4", "int8", "bigint",
        "numeric", "decimal", "number", "tinyint", "mediumint"
    );
    private static final Set<String> TEXT_TYPES = Set.of(
        "char", "character", "varchar", "character varying", "text", "nvarchar", "nvarchar2", "string"
    );

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final DataSourceCatalogService catalogService;
    private final DataCatalogMapper catalogMapper;
    private final DataGovernanceMapper mapper;
    private final JsonMapper jsonMapper;

    public DataGovernanceService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        DataSourceCatalogService catalogService,
        DataCatalogMapper catalogMapper,
        DataGovernanceMapper mapper,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.catalogService = catalogService;
        this.catalogMapper = catalogMapper;
        this.mapper = mapper;
        this.jsonMapper = jsonMapper;
    }

    public List<MetricView> metrics(Long datasetId) {
        requireDataset(datasetId, "view");
        return mapper.selectLatestMetrics(datasetId).stream().map(this::metricView).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public MetricView createMetric(Long datasetId, CreateMetricRequest request) {
        CurrentPrincipal principal = requireDataset(datasetId, "update").principal();
        String key = request.metricKey().strip();
        if (mapper.countMetricKey(datasetId, key) > 0) {
            throw conflict("指标标识已存在，历史版本不可复用");
        }
        LocalDateTime now = LocalDateTime.now();
        AgentDataMetric metric = new AgentDataMetric();
        metric.setId(idGenerator.nextId());
        metric.setDatasetId(datasetId);
        metric.setMetricKey(key);
        applyMetric(metric, request.name(), request.description(), request.calculationLogic(), request.unit(), request.status());
        metric.setVersionNo(1);
        metric.setCreatedBy(principal.id());
        metric.setCreatedAt(now);
        metric.setUpdatedAt(now);
        if (mapper.insertMetric(metric) != 1) {
            throw conflict("指标创建失败");
        }
        MetricView after = metricView(metric);
        change(datasetId, "metric", metric.getId(), "create", null, after, principal.id(), now);
        return after;
    }

    @Transactional(rollbackFor = Exception.class)
    public MetricView updateMetric(Long datasetId, Long metricId, UpdateMetricRequest request) {
        CurrentPrincipal principal = requireDataset(datasetId, "update").principal();
        AgentDataMetric current = requireMetric(datasetId, metricId);
        if (!Objects.equals(current.getVersionNo(), request.versionNo())) {
            throw conflict("指标已被其他操作修改，请刷新后重试");
        }
        MetricView before = metricView(current);
        LocalDateTime now = LocalDateTime.now();
        AgentDataMetric next = new AgentDataMetric();
        next.setId(idGenerator.nextId());
        next.setDatasetId(datasetId);
        next.setMetricKey(current.getMetricKey());
        applyMetric(next, request.name(), request.description(), request.calculationLogic(), request.unit(), request.status());
        next.setVersionNo(current.getVersionNo() + 1);
        next.setCreatedBy(principal.id());
        next.setCreatedAt(now);
        next.setUpdatedAt(now);
        if (mapper.insertMetric(next) != 1) {
            throw conflict("指标版本保存失败");
        }
        MetricView after = metricView(next);
        change(datasetId, "metric", next.getId(), "update", before, after, principal.id(), now);
        return after;
    }

    @Transactional(rollbackFor = Exception.class)
    public void archiveMetric(Long datasetId, Long metricId) {
        CurrentPrincipal principal = requireDataset(datasetId, "update").principal();
        AgentDataMetric current = requireMetric(datasetId, metricId);
        if ("inactive".equals(current.getStatus())) {
            return;
        }
        MetricView before = metricView(current);
        LocalDateTime now = LocalDateTime.now();
        AgentDataMetric next = new AgentDataMetric();
        next.setId(idGenerator.nextId());
        next.setDatasetId(datasetId);
        next.setMetricKey(current.getMetricKey());
        applyMetric(next, current.getName(), current.getDescription(), current.getCalculationLogic(), current.getUnit(), "inactive");
        next.setVersionNo(current.getVersionNo() + 1);
        next.setCreatedBy(principal.id());
        next.setCreatedAt(now);
        next.setUpdatedAt(now);
        if (mapper.insertMetric(next) != 1) {
            throw conflict("指标归档失败");
        }
        change(datasetId, "metric", next.getId(), "archive", before, metricView(next), principal.id(), now);
    }

    public List<RelationshipView> relationships(Long datasetId) {
        requireDataset(datasetId, "view");
        Map<Long, AgentDataTable> tables = tableMap(datasetId);
        return mapper.selectRelationships(datasetId).stream()
            .map(relation -> relationshipView(relation, tables)).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public RelationshipView createRelationship(Long datasetId, CreateRelationshipRequest request) {
        CurrentPrincipal principal = requireDataset(datasetId, "update").principal();
        Map<Long, AgentDataTable> tables = tableMap(datasetId);
        validateRelationship(datasetId, null, request.sourceTableId(), request.targetTableId(),
            request.joinCondition(), request.status(), tables);
        LocalDateTime now = LocalDateTime.now();
        AgentDataRelation relation = new AgentDataRelation();
        relation.setId(idGenerator.nextId());
        relation.setDatasetId(datasetId);
        applyRelationship(relation, request.sourceTableId(), request.targetTableId(), request.joinType(),
            request.joinCondition(), request.description(), request.status());
        relation.setRevisionNo(1);
        relation.setCreatedBy(principal.id());
        relation.setCreatedAt(now);
        relation.setUpdatedBy(principal.id());
        relation.setUpdatedAt(now);
        if (mapper.insertRelationship(relation) != 1) {
            throw conflict("数据关系创建失败");
        }
        RelationshipView after = relationshipView(relation, tables);
        change(datasetId, "relationship", relation.getId(), "create", null, after, principal.id(), now);
        return after;
    }

    @Transactional(rollbackFor = Exception.class)
    public RelationshipView updateRelationship(
        Long datasetId,
        Long relationshipId,
        UpdateRelationshipRequest request
    ) {
        CurrentPrincipal principal = requireDataset(datasetId, "update").principal();
        AgentDataRelation relation = requireRelationship(datasetId, relationshipId);
        if (!Objects.equals(relation.getRevisionNo(), request.revisionNo())) {
            throw conflict("数据关系已被其他操作修改，请刷新后重试");
        }
        Map<Long, AgentDataTable> tables = tableMap(datasetId);
        RelationshipView before = relationshipView(relation, tables);
        validateRelationship(datasetId, relationshipId, request.sourceTableId(), request.targetTableId(),
            request.joinCondition(), request.status(), tables);
        applyRelationship(relation, request.sourceTableId(), request.targetTableId(), request.joinType(),
            request.joinCondition(), request.description(), request.status());
        relation.setUpdatedBy(principal.id());
        relation.setUpdatedAt(LocalDateTime.now());
        if (mapper.updateRelationship(relation) != 1) {
            throw conflict("数据关系已被其他操作修改，请刷新后重试");
        }
        relation.setRevisionNo(relation.getRevisionNo() + 1);
        RelationshipView after = relationshipView(relation, tables);
        change(datasetId, "relationship", relation.getId(), "update", before, after,
            principal.id(), relation.getUpdatedAt());
        return after;
    }

    @Transactional(rollbackFor = Exception.class)
    public void archiveRelationship(Long datasetId, Long relationshipId) {
        CurrentPrincipal principal = requireDataset(datasetId, "update").principal();
        AgentDataRelation relation = requireRelationship(datasetId, relationshipId);
        if ("inactive".equals(relation.getStatus())) {
            return;
        }
        Map<Long, AgentDataTable> tables = tableMap(datasetId);
        RelationshipView before = relationshipView(relation, tables);
        relation.setStatus("inactive");
        relation.setUpdatedBy(principal.id());
        relation.setUpdatedAt(LocalDateTime.now());
        if (mapper.updateRelationship(relation) != 1) {
            throw conflict("数据关系已被其他操作修改，请刷新后重试");
        }
        relation.setRevisionNo(relation.getRevisionNo() + 1);
        change(datasetId, "relationship", relation.getId(), "archive", before,
            relationshipView(relation, tables), principal.id(), relation.getUpdatedAt());
    }

    public RowPolicyView rowPolicy(Long datasetId) {
        Access access = requireDataset(datasetId, "view");
        return rowPolicyView(access.dataset());
    }

    @Transactional(rollbackFor = Exception.class)
    public RowPolicyView updateRowPolicy(Long datasetId, UpdateRowPolicyRequest request) {
        Access access = requireDataset(datasetId, "update");
        AgentDataDataset dataset = access.dataset();
        if (!Objects.equals(dataset.getRevisionNo(), request.revisionNo())) {
            throw conflict("数据集已被其他操作修改，请刷新后重试");
        }
        List<RowPolicyRule> rules = List.copyOf(request.rules());
        if (request.enabled() && rules.isEmpty()) {
            throw badRequest("启用行级权限时至少需要一条规则");
        }
        validateRules(datasetId, rules);
        RowPolicyView before = rowPolicyView(dataset);
        String policyJson = jsonMapper.writeValueAsString(new PolicyDocument(rules));
        LocalDateTime now = LocalDateTime.now();
        if (mapper.updateRowPolicy(datasetId, request.revisionNo(), request.enabled(), policyJson,
            access.principal().id(), now) != 1) {
            throw conflict("数据集已被其他操作修改，请刷新后重试");
        }
        RowPolicyView after = new RowPolicyView(
            datasetId, request.revisionNo() + 1, request.enabled(), rules, now
        );
        change(datasetId, "row_policy", datasetId, "update", before, after,
            access.principal().id(), now);
        return after;
    }

    public List<MetadataChangeView> changes(Long datasetId, int limit) {
        requireDataset(datasetId, "view");
        return mapper.selectChanges(datasetId, limit).stream().map(this::changeView).toList();
    }

    private Access requireDataset(Long datasetId, String action) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentDataDataset dataset = catalogService.requireDataset(datasetId);
        authorizationEnforcer.requireAllowed(principal, catalogService.datasetContext(dataset, action));
        return new Access(principal, dataset);
    }

    private AgentDataMetric requireMetric(Long datasetId, Long metricId) {
        AgentDataMetric metric = mapper.selectLatestMetricForUpdate(datasetId, metricId);
        if (metric == null) {
            throw notFound("指标不存在或不是最新版本");
        }
        return metric;
    }

    private AgentDataRelation requireRelationship(Long datasetId, Long relationshipId) {
        AgentDataRelation relationship = mapper.selectRelationshipForUpdate(datasetId, relationshipId);
        if (relationship == null) {
            throw notFound("数据关系不存在");
        }
        return relationship;
    }

    private void applyMetric(
        AgentDataMetric metric,
        String name,
        String description,
        String calculationLogic,
        String unit,
        String status
    ) {
        metric.setName(name.strip());
        metric.setDescription(trimToNull(description));
        metric.setCalculationLogic(calculationLogic.strip());
        metric.setUnit(trimToNull(unit));
        metric.setStatus(status);
    }

    private void applyRelationship(
        AgentDataRelation relation,
        Long sourceTableId,
        Long targetTableId,
        String joinType,
        String joinCondition,
        String description,
        String status
    ) {
        relation.setSourceTableId(sourceTableId);
        relation.setTargetTableId(targetTableId);
        relation.setJoinType(joinType);
        relation.setJoinCondition(joinCondition.strip());
        relation.setDescription(trimToNull(description));
        relation.setStatus(status);
    }

    private void validateRelationship(
        Long datasetId,
        Long excludeId,
        Long sourceTableId,
        Long targetTableId,
        String joinCondition,
        String status,
        Map<Long, AgentDataTable> tables
    ) {
        AgentDataTable source = activeTable(tables, sourceTableId);
        AgentDataTable target = activeTable(tables, targetTableId);
        if ("active".equals(status)
            && mapper.countActiveRelationship(datasetId, sourceTableId, targetTableId, excludeId) > 0) {
            throw conflict("相同方向的活动数据关系已存在");
        }
        Expression expression;
        try {
            expression = CCJSqlParserUtil.parseCondExpression(joinCondition.strip());
        } catch (JSQLParserException exception) {
            throw badRequest("关联条件无法解析");
        }
        if (!(expression instanceof EqualsTo equals)
            || !(equals.getLeftExpression() instanceof Column left)
            || !(equals.getRightExpression() instanceof Column right)) {
            throw badRequest("一期关联条件只允许两个字段之间的等值连接");
        }
        validateJoinColumn(left, source, target, datasetId);
        validateJoinColumn(right, source, target, datasetId);
        String leftTable = tableQualifier(left);
        String rightTable = tableQualifier(right);
        if (leftTable.equalsIgnoreCase(rightTable)) {
            throw badRequest("关联条件必须分别引用源表和目标表");
        }
    }

    private void validateJoinColumn(Column column, AgentDataTable source, AgentDataTable target, Long datasetId) {
        String qualifier = tableQualifier(column);
        AgentDataTable table;
        if (matchesTable(source, qualifier)) {
            table = source;
        } else if (matchesTable(target, qualifier)) {
            table = target;
        } else {
            throw badRequest("关联条件只能引用所选源表和目标表");
        }
        String name = unquote(column.getUnquotedColumnName());
        boolean found = catalogMapper.selectColumns(datasetId).stream()
            .anyMatch(item -> item.getTableId().equals(table.getId())
                && item.getPhysicalName().equalsIgnoreCase(name)
                && "active".equals(item.getStatus()) && Boolean.TRUE.equals(item.getMetadataPresent()));
        if (!found) {
            throw badRequest("关联条件引用了不存在或未启用的字段：" + name);
        }
    }

    private String tableQualifier(Column column) {
        String qualifier = column.getTable() == null ? null : unquote(column.getTable().getUnquotedName());
        if (qualifier == null || qualifier.isBlank()) {
            throw badRequest("关联条件中的字段必须带表名限定");
        }
        return qualifier;
    }

    private boolean matchesTable(AgentDataTable table, String qualifier) {
        return table.getPhysicalName().equalsIgnoreCase(qualifier)
            || table.getTableKey().equalsIgnoreCase(qualifier);
    }

    private void validateRules(Long datasetId, List<RowPolicyRule> rules) {
        Map<Long, AgentDataTable> tables = tableMap(datasetId);
        Map<Long, AgentDataColumn> columns = new HashMap<>();
        for (AgentDataColumn column : catalogMapper.selectColumns(datasetId)) {
            columns.put(column.getId(), column);
        }
        Set<String> unique = new HashSet<>();
        for (RowPolicyRule rule : rules) {
            AgentDataTable table = activeTable(tables, rule.tableId());
            AgentDataColumn column = columns.get(rule.columnId());
            if (column == null || !column.getTableId().equals(table.getId())
                || !"active".equals(column.getStatus()) || !Boolean.TRUE.equals(column.getMetadataPresent())) {
                throw badRequest("行策略字段不属于所选活动数据表");
            }
            String key = rule.tableId() + ":" + rule.columnId();
            if (!unique.add(key)) {
                throw badRequest("同一字段不能重复配置行策略");
            }
            String baseType = normalizeType(column.getDataType());
            if ("principal_id".equals(rule.valueSource()) && !NUMERIC_TYPES.contains(baseType)) {
                throw badRequest("principal_id 行策略只能绑定数值字段");
            }
            if ("principal_username".equals(rule.valueSource()) && !TEXT_TYPES.contains(baseType)) {
                throw badRequest("principal_username 行策略只能绑定文本字段");
            }
        }
    }

    private String normalizeType(String dataType) {
        String value = dataType == null ? "" : dataType.strip().toLowerCase(Locale.ROOT);
        int parameters = value.indexOf('(');
        return parameters < 0 ? value : value.substring(0, parameters).strip();
    }

    private AgentDataTable activeTable(Map<Long, AgentDataTable> tables, Long id) {
        AgentDataTable table = tables.get(id);
        if (table == null || !"active".equals(table.getStatus()) || !Boolean.TRUE.equals(table.getMetadataPresent())) {
            throw badRequest("数据关系或行策略只能引用活动数据表");
        }
        return table;
    }

    private Map<Long, AgentDataTable> tableMap(Long datasetId) {
        Map<Long, AgentDataTable> result = new HashMap<>();
        for (AgentDataTable table : catalogMapper.selectTables(datasetId)) {
            result.put(table.getId(), table);
        }
        return result;
    }

    private RowPolicyView rowPolicyView(AgentDataDataset dataset) {
        List<RowPolicyRule> rules = List.of();
        if (dataset.getRowPolicyJson() != null && !dataset.getRowPolicyJson().isBlank()
            && !"{}".equals(dataset.getRowPolicyJson().strip())) {
            try {
                PolicyDocument document = jsonMapper.readValue(dataset.getRowPolicyJson(), POLICY_TYPE);
                rules = document == null || document.rules() == null ? List.of() : List.copyOf(document.rules());
            } catch (RuntimeException exception) {
                throw new ServiceException("数据集行策略配置损坏", HttpStatus.ERROR);
            }
        }
        return new RowPolicyView(
            dataset.getId(), dataset.getRevisionNo(), Boolean.TRUE.equals(dataset.getEnableRowPolicy()),
            rules, dataset.getUpdateTime()
        );
    }

    private MetricView metricView(AgentDataMetric metric) {
        return new MetricView(
            metric.getId(), metric.getDatasetId(), metric.getMetricKey(), metric.getName(),
            metric.getDescription(), metric.getCalculationLogic(), metric.getUnit(), metric.getStatus(),
            metric.getVersionNo(), metric.getCreatedBy(), metric.getCreatedAt(), metric.getUpdatedAt()
        );
    }

    private RelationshipView relationshipView(AgentDataRelation relation, Map<Long, AgentDataTable> tables) {
        AgentDataTable source = tables.get(relation.getSourceTableId());
        AgentDataTable target = tables.get(relation.getTargetTableId());
        return new RelationshipView(
            relation.getId(), relation.getDatasetId(), relation.getSourceTableId(), relation.getTargetTableId(),
            tableName(source), tableName(target), relation.getJoinType(), relation.getJoinCondition(),
            relation.getDescription(), relation.getStatus(), relation.getRevisionNo(), relation.getCreatedBy(),
            relation.getCreatedAt(), relation.getUpdatedBy(), relation.getUpdatedAt()
        );
    }

    private String tableName(AgentDataTable table) {
        return table == null ? null : table.getPhysicalSchema() + "." + table.getPhysicalName();
    }

    private MetadataChangeView changeView(MetadataChangeRow row) {
        return new MetadataChangeView(
            row.getId(), row.getDatasetId(), row.getResourceType(), row.getResourceId(), row.getAction(),
            row.getBeforeJson(), row.getAfterJson(), row.getBeforeHash(), row.getAfterHash(),
            row.getActorId(), row.getCreatedAt()
        );
    }

    private void change(
        Long datasetId,
        String resourceType,
        Long resourceId,
        String action,
        Object before,
        Object after,
        Long actorId,
        LocalDateTime now
    ) {
        String beforeJson = before == null ? null : jsonMapper.writeValueAsString(before);
        String afterJson = after == null ? null : jsonMapper.writeValueAsString(after);
        MetadataChangeRow row = new MetadataChangeRow();
        row.setId(idGenerator.nextId());
        row.setDatasetId(datasetId);
        row.setResourceType(resourceType);
        row.setResourceId(resourceId);
        row.setAction(action);
        row.setBeforeJson(beforeJson);
        row.setAfterJson(afterJson);
        row.setBeforeHash(beforeJson == null ? null : ContentHashing.sha256(beforeJson));
        row.setAfterHash(afterJson == null ? null : ContentHashing.sha256(afterJson));
        row.setActorId(actorId);
        row.setCreatedAt(now);
        if (mapper.insertChange(row) != 1) {
            throw conflict("元数据变更记录写入失败");
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String unquote(String value) {
        if (value == null) {
            return null;
        }
        String result = value;
        if (result.length() >= 2 && ((result.startsWith("\"") && result.endsWith("\""))
            || (result.startsWith("`") && result.endsWith("`")))) {
            result = result.substring(1, result.length() - 1);
        }
        return result.replace("\"\"", "\"");
    }

    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    private ServiceException notFound(String message) {
        return new ServiceException(message, HttpStatus.NOT_FOUND);
    }

    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    private record Access(CurrentPrincipal principal, AgentDataDataset dataset) {
    }

    public record PolicyDocument(List<RowPolicyRule> rules) {
        public PolicyDocument {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }
}
