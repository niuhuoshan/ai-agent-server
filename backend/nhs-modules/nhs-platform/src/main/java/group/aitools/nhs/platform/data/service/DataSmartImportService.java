package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataProfileJob;
import group.aitools.nhs.platform.data.domain.AgentDataProfileRelationRecommendation;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.domain.AgentDataSmartImportItem;
import group.aitools.nhs.platform.data.domain.AgentDataSmartImportPreview;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.data.domain.AgentDataTableProfile;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.mapper.DataGovernanceMapper;
import group.aitools.nhs.platform.data.mapper.DataProfileMapper;
import group.aitools.nhs.platform.data.persistence.row.MetadataChangeRow;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.ApplySmartImportRequest;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.CreateRelationshipRequest;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.ColumnImportProposalView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.ColumnGovernanceSnapshotView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.ColumnProfileView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.CreateSmartImportPreviewRequest;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.RelationshipImportProposalView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.SmartImportAppliedItemView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.SmartImportApplyResultView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.SmartImportItemView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.SmartImportPreviewView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.TableImportProposalView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.TableGovernanceSnapshotView;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds tamper-evident previews and atomically applies only explicitly selected changes. */
@Service
public class DataSmartImportService {

    private static final TypeReference<List<ColumnProfileView>> COLUMN_PROFILES = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final DataSourceCatalogService catalogService;
    private final DataCatalogMapper catalogMapper;
    private final DataProfileMapper mapper;
    private final DataGovernanceMapper governanceMapper;
    private final DataGovernanceService governanceService;
    private final JsonMapper jsonMapper;

    public DataSmartImportService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        DataSourceCatalogService catalogService,
        DataCatalogMapper catalogMapper,
        DataProfileMapper mapper,
        DataGovernanceMapper governanceMapper,
        DataGovernanceService governanceService,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.catalogService = catalogService;
        this.catalogMapper = catalogMapper;
        this.mapper = mapper;
        this.governanceMapper = governanceMapper;
        this.governanceService = governanceService;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public SmartImportPreviewView createPreview(
        Long datasetId,
        CreateSmartImportPreviewRequest request
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentDataDataset dataset = activeAccess(datasetId, principal, "update");
        AgentDataProfileJob job = request.profileJobId() == null
            ? mapper.selectLatestSucceededJob(datasetId)
            : mapper.selectJob(datasetId, request.profileJobId());
        if (job == null || !"succeeded".equals(job.getStatus())) {
            throw conflict("智能导入只能使用已完成的画像任务");
        }
        LinkedHashSet<Long> selected = new LinkedHashSet<>(request.tableIds());
        if (selected.size() != request.tableIds().size()) {
            throw new ServiceException("智能导入表选择不能包含重复项", HttpStatus.BAD_REQUEST);
        }
        Map<Long, AgentDataTableProfile> profiles = mapper.selectJobProfiles(datasetId, job.getId()).stream()
            .filter(profile -> selected.contains(profile.getTableId()))
            .collect(Collectors.toMap(AgentDataTableProfile::getTableId, Function.identity()));
        List<Long> unavailable = selected.stream()
            .filter(tableId -> !profiles.containsKey(tableId)
                || Boolean.TRUE.equals(profiles.get(tableId).getIgnored()))
            .toList();
        if (!unavailable.isEmpty()) {
            throw new ServiceException(
                "所选数据表没有成功画像或已被忽略：" + unavailable,
                HttpStatus.BAD_REQUEST
            );
        }
        Map<Long, AgentDataTable> tables = catalogMapper.selectTables(datasetId).stream()
            .collect(Collectors.toMap(AgentDataTable::getId, Function.identity()));
        Map<Long, AgentDataColumn> columns = catalogMapper.selectColumns(datasetId).stream()
            .collect(Collectors.toMap(AgentDataColumn::getId, Function.identity()));
        LocalDateTime now = LocalDateTime.now();
        AgentDataSmartImportPreview preview = new AgentDataSmartImportPreview();
        preview.setId(idGenerator.nextId());
        preview.setDatasetId(datasetId);
        preview.setProfileJobId(job.getId());
        preview.setStatus("draft");
        preview.setDatasetRevision(dataset.getRevisionNo());
        preview.setRevisionNo(1);
        preview.setExpiresAt(now.plusMinutes(30));
        preview.setCreatedBy(principal.id());
        preview.setCreatedAt(now);
        if (mapper.insertPreview(preview) != 1) {
            throw conflict("智能导入预览创建失败");
        }
        for (Long tableId : selected) {
            AgentDataTable table = tables.get(tableId);
            AgentDataTableProfile profile = profiles.get(tableId);
            if (table == null || !Boolean.TRUE.equals(table.getMetadataPresent())) {
                throw conflict("所选数据表的同步元数据已失效");
            }
            TableImportProposalView proposal = tableProposal(table, profile, columns);
            insertItem(preview.getId(), "table", profile.getId(), proposal, now);
        }
        for (AgentDataProfileRelationRecommendation recommendation
            : mapper.selectJobRecommendations(datasetId, job.getId())) {
            if (!"pending".equals(recommendation.getStatus())
                || !selected.contains(recommendation.getSourceTableId())
                || !selected.contains(recommendation.getTargetTableId())) {
                continue;
            }
            RelationshipImportProposalView proposal = relationshipProposal(
                recommendation, profiles, tables, columns
            );
            insertItem(preview.getId(), "relationship", recommendation.getId(), proposal, now);
        }
        change(
            datasetId, "smart_import", preview.getId(), "create", null,
            Map.of(
                "previewId", preview.getId(), "profileJobId", job.getId(),
                "tableCount", selected.size(), "datasetRevision", dataset.getRevisionNo()
            ), principal.id(), now
        );
        return previewView(preview, mapper.selectPreviewItems(preview.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public SmartImportPreviewView preview(Long datasetId, Long previewId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorize(datasetId, principal, "read");
        LocalDateTime now = LocalDateTime.now();
        mapper.expirePreview(datasetId, previewId, now);
        AgentDataSmartImportPreview preview = mapper.selectPreview(datasetId, previewId);
        if (preview == null) {
            throw notFound("智能导入预览不存在");
        }
        return previewView(preview, mapper.selectPreviewItems(previewId));
    }

    @Transactional(rollbackFor = Exception.class)
    public SmartImportApplyResultView apply(
        Long datasetId,
        Long previewId,
        ApplySmartImportRequest request
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        activeAccess(datasetId, principal, "update");
        LocalDateTime now = LocalDateTime.now();
        AgentDataSmartImportPreview preview = mapper.selectPreviewForUpdate(datasetId, previewId);
        if (preview == null) {
            throw notFound("智能导入预览不存在");
        }
        if (!"draft".equals(preview.getStatus()) || preview.getExpiresAt().compareTo(now) <= 0) {
            throw conflict("智能导入预览已应用或已过期");
        }
        if (!request.revisionNo().equals(preview.getRevisionNo())) {
            throw conflict("智能导入预览版本已变化，请刷新后重试");
        }
        AgentDataDataset dataset = mapper.selectDatasetForUpdate(datasetId);
        if (dataset == null || !preview.getDatasetRevision().equals(dataset.getRevisionNo())) {
            throw conflict("数据集在预览生成后已变化，请重新生成预览");
        }
        List<AgentDataSmartImportItem> items = mapper.selectPreviewItemsForUpdate(previewId);
        Map<Long, AgentDataSmartImportItem> byId = items.stream()
            .collect(Collectors.toMap(AgentDataSmartImportItem::getId, Function.identity()));
        LinkedHashSet<Long> selected = new LinkedHashSet<>(request.itemIds());
        if (selected.size() != request.itemIds().size()
            || selected.stream().anyMatch(id -> !byId.containsKey(id))) {
            throw new ServiceException("智能导入选择包含重复或未知预览项", HttpStatus.BAD_REQUEST);
        }
        List<SmartImportAppliedItemView> applied = new ArrayList<>();
        for (Long itemId : selected) {
            AgentDataSmartImportItem item = byId.get(itemId);
            if (!"available".equals(item.getStatus())) {
                throw conflict("智能导入预览项已处理，请重新生成预览");
            }
            verifyHash(item);
            Long appliedId = switch (item.getItemType()) {
                case "table" -> applyTable(datasetId, item, principal.id(), now);
                case "relationship" -> applyRelationship(datasetId, item, now);
                default -> throw new IllegalStateException("未知智能导入项类型");
            };
            if (mapper.markPreviewItemApplied(previewId, itemId, appliedId, now) != 1) {
                throw conflict("智能导入预览项状态已变化");
            }
            applied.add(new SmartImportAppliedItemView(
                itemId, item.getItemType(), item.getResourceId(), appliedId
            ));
        }
        mapper.skipRemainingPreviewItems(previewId, now);
        if (mapper.advanceDatasetRevision(
            datasetId, preview.getDatasetRevision(), principal.id(), now
        ) != 1) {
            throw conflict("数据集版本已变化，请重新生成预览");
        }
        if (mapper.completePreview(
            datasetId, previewId, request.revisionNo(), principal.id(), now
        ) != 1) {
            throw conflict("智能导入预览状态已变化");
        }
        change(
            datasetId, "smart_import", previewId, "update",
            Map.of("status", "draft", "revisionNo", preview.getRevisionNo()),
            Map.of(
                "status", "applied", "selectedItemIds", List.copyOf(selected),
                "appliedCount", applied.size(), "datasetRevision", dataset.getRevisionNo() + 1
            ), principal.id(), now
        );
        AgentDataSmartImportPreview completed = mapper.selectPreview(datasetId, previewId);
        return new SmartImportApplyResultView(
            previewView(completed, mapper.selectPreviewItems(previewId)), applied
        );
    }

    private Long applyTable(
        Long datasetId,
        AgentDataSmartImportItem item,
        Long actorId,
        LocalDateTime now
    ) {
        TableImportProposalView proposal = jsonMapper.readValue(
            item.getProposedJson(), TableImportProposalView.class
        );
        if (!item.getResourceId().equals(proposal.profileId())) {
            throw conflict("表增强预览来源不一致");
        }
        AgentDataTableProfile profile = mapper.selectLatestProfile(datasetId, proposal.tableId());
        if (profile == null || !profile.getId().equals(proposal.profileId())
            || !profile.getRevisionNo().equals(proposal.profileRevision())
            || Boolean.TRUE.equals(profile.getIgnored())) {
            throw conflict("表画像已变化，请重新生成智能导入预览");
        }
        AgentDataTable table = mapper.selectTableForSmartImport(datasetId, proposal.tableId());
        if (table == null || !datasetId.equals(table.getDatasetId())
            || !Boolean.TRUE.equals(table.getMetadataPresent())) {
            throw conflict("智能导入目标表已失效");
        }
        requireTableSnapshot(table, proposal.expected());
        List<AgentDataColumn> lockedColumns = mapper.selectColumnsForSmartImport(
            datasetId, proposal.tableId()
        );
        List<AgentDataColumn> currentColumns = lockedColumns.stream()
            .filter(column -> "active".equals(column.getStatus()))
            .filter(column -> Boolean.TRUE.equals(column.getMetadataPresent()))
            .sorted(Comparator.comparing(AgentDataColumn::getId))
            .toList();
        String currentSourceHash = DataTableProfiler.structureHash(table, currentColumns);
        if (!proposal.sourceHash().equals(profile.getSourceHash())
            || !proposal.sourceHash().equals(currentSourceHash)) {
            throw conflict("智能导入目标表结构已变化，请重新画像并生成预览");
        }
        Set<Long> currentColumnIds = currentColumns.stream()
            .map(AgentDataColumn::getId).collect(Collectors.toSet());
        Set<Long> proposedColumnIds = proposal.columnUpdates().stream()
            .map(ColumnImportProposalView::columnId).collect(Collectors.toSet());
        if (proposedColumnIds.size() != proposal.columnUpdates().size()
            || !currentColumnIds.equals(proposedColumnIds)) {
            throw conflict("智能导入字段集合已变化，请重新生成预览");
        }
        Map<Long, AgentDataColumn> lockedColumnsById = lockedColumns.stream()
            .collect(Collectors.toMap(AgentDataColumn::getId, Function.identity()));
        for (ColumnImportProposalView column : proposal.columnUpdates()) {
            AgentDataColumn current = lockedColumnsById.get(column.columnId());
            if (current == null || !proposal.tableId().equals(current.getTableId())
                || !Boolean.TRUE.equals(current.getMetadataPresent())) {
                throw conflict("智能导入目标字段已失效");
            }
            requireColumnSnapshot(current, column.expected());
        }
        Map<String, Object> beforeTable = tableAudit(table);
        if (catalogMapper.updateTableGovernance(
            datasetId, proposal.tableId(), proposal.displayName(), proposal.description(),
            proposal.status(), actorId, now
        ) != 1) {
            throw conflict("智能导入目标表更新失败");
        }
        AgentDataTable afterTable = catalogMapper.selectTable(proposal.tableId());
        if (afterTable == null) {
            throw conflict("智能导入目标表更新后不可读取");
        }
        change(
            datasetId, "table", proposal.tableId(), "update",
            beforeTable, tableAudit(afterTable), actorId, now
        );
        for (ColumnImportProposalView column : proposal.columnUpdates()) {
            AgentDataColumn current = lockedColumnsById.get(column.columnId());
            Map<String, Object> beforeColumn = columnAudit(current);
            if (catalogMapper.updateColumnGovernance(
                datasetId, column.columnId(), column.displayName(), column.description(),
                column.sensitive(), column.status(), now
            ) != 1) {
                throw conflict("智能导入目标字段更新失败");
            }
            AgentDataColumn afterColumn = catalogMapper.selectColumn(column.columnId());
            if (afterColumn == null) {
                throw conflict("智能导入目标字段更新后不可读取");
            }
            change(
                datasetId, "column", column.columnId(), "update",
                beforeColumn, columnAudit(afterColumn), actorId, now
            );
        }
        return proposal.tableId();
    }

    private Long applyRelationship(
        Long datasetId,
        AgentDataSmartImportItem item,
        LocalDateTime now
    ) {
        RelationshipImportProposalView proposal = jsonMapper.readValue(
            item.getProposedJson(), RelationshipImportProposalView.class
        );
        if (!item.getResourceId().equals(proposal.recommendationId())) {
            throw conflict("关系建议预览来源不一致");
        }
        AgentDataProfileRelationRecommendation recommendation = mapper.selectRecommendationForUpdate(
            datasetId, proposal.recommendationId()
        );
        if (recommendation == null || !"pending".equals(recommendation.getStatus())
            || !proposal.sourceTableId().equals(recommendation.getSourceTableId())
            || !proposal.sourceColumnId().equals(recommendation.getSourceColumnId())
            || !proposal.targetTableId().equals(recommendation.getTargetTableId())
            || !proposal.targetColumnId().equals(recommendation.getTargetColumnId())
            || !proposal.joinType().equals(recommendation.getJoinType())
            || !proposal.joinCondition().equals(recommendation.getJoinCondition())) {
            throw conflict("关系建议已变化，请重新生成智能导入预览");
        }
        Map<Long, LockedEndpoint> endpoints = lockRelationshipEndpoints(datasetId, proposal);
        requireRelationshipBaseline(
            datasetId,
            endpoints.get(proposal.sourceTableId()),
            proposal.sourceColumnId(),
            proposal.sourceProfileId(),
            proposal.sourceProfileRevision(),
            proposal.sourceStructureHash(),
            proposal.sourceTableStateHash(),
            proposal.sourceColumnStateHash()
        );
        requireRelationshipBaseline(
            datasetId,
            endpoints.get(proposal.targetTableId()),
            proposal.targetColumnId(),
            proposal.targetProfileId(),
            proposal.targetProfileRevision(),
            proposal.targetStructureHash(),
            proposal.targetTableStateHash(),
            proposal.targetColumnStateHash()
        );
        var relation = governanceService.createRelationship(
            datasetId,
            new CreateRelationshipRequest(
                proposal.sourceTableId(), proposal.targetTableId(), proposal.joinType(),
                proposal.joinCondition(), proposal.description(), "active"
            )
        );
        if (mapper.markRecommendationApplied(
            datasetId, proposal.recommendationId(), relation.id(), now
        ) != 1) {
            throw conflict("关系建议状态已变化");
        }
        return relation.id();
    }

    private Map<Long, LockedEndpoint> lockRelationshipEndpoints(
        Long datasetId,
        RelationshipImportProposalView proposal
    ) {
        List<Long> tableIds = List.of(proposal.sourceTableId(), proposal.targetTableId()).stream()
            .distinct().sorted().toList();
        Map<Long, LockedEndpoint> result = new LinkedHashMap<>();
        for (Long tableId : tableIds) {
            AgentDataTable table = mapper.selectTableForSmartImport(datasetId, tableId);
            List<AgentDataColumn> columns = mapper.selectColumnsForSmartImport(datasetId, tableId);
            if (table == null || !datasetId.equals(table.getDatasetId())
                || !"active".equals(table.getStatus())
                || !Boolean.TRUE.equals(table.getMetadataPresent())) {
                throw conflict("关系建议引用的表已失效");
            }
            Map<Long, AgentDataColumn> byId = columns.stream()
                .collect(Collectors.toMap(AgentDataColumn::getId, Function.identity()));
            List<AgentDataColumn> active = columns.stream()
                .filter(column -> "active".equals(column.getStatus()))
                .filter(column -> Boolean.TRUE.equals(column.getMetadataPresent()))
                .sorted(Comparator.comparing(AgentDataColumn::getId))
                .toList();
            result.put(tableId, new LockedEndpoint(table, byId, active));
        }
        return Map.copyOf(result);
    }

    private void requireRelationshipBaseline(
        Long datasetId,
        LockedEndpoint endpoint,
        Long columnId,
        Long profileId,
        Integer profileRevision,
        String structureHash,
        String expectedTableStateHash,
        String expectedColumnStateHash
    ) {
        if (endpoint == null) {
            throw conflict("关系建议引用的表已失效");
        }
        AgentDataColumn column = endpoint.columns().get(columnId);
        if (column == null || !endpoint.table().getId().equals(column.getTableId())
            || !"active".equals(column.getStatus())
            || !Boolean.TRUE.equals(column.getMetadataPresent())) {
            throw conflict("关系建议引用的字段已失效");
        }
        AgentDataTableProfile profile = mapper.selectLatestProfile(datasetId, endpoint.table().getId());
        String currentStructureHash = DataTableProfiler.structureHash(
            endpoint.table(), endpoint.activeColumns()
        );
        if (profile == null || Boolean.TRUE.equals(profile.getIgnored())
            || !Objects.equals(profileId, profile.getId())
            || !Objects.equals(profileRevision, profile.getRevisionNo())
            || !Objects.equals(structureHash, profile.getSourceHash())
            || !Objects.equals(structureHash, currentStructureHash)
            || !Objects.equals(expectedTableStateHash, tableStateHash(endpoint.table()))
            || !Objects.equals(expectedColumnStateHash, columnStateHash(column))) {
            throw conflict("关系建议的画像或端点结构已变化，请重新生成预览");
        }
    }

    private RelationshipImportProposalView relationshipProposal(
        AgentDataProfileRelationRecommendation recommendation,
        Map<Long, AgentDataTableProfile> profiles,
        Map<Long, AgentDataTable> tables,
        Map<Long, AgentDataColumn> columns
    ) {
        AgentDataTableProfile sourceProfile = profiles.get(recommendation.getSourceTableId());
        AgentDataTableProfile targetProfile = profiles.get(recommendation.getTargetTableId());
        AgentDataTable sourceTable = tables.get(recommendation.getSourceTableId());
        AgentDataTable targetTable = tables.get(recommendation.getTargetTableId());
        AgentDataColumn sourceColumn = columns.get(recommendation.getSourceColumnId());
        AgentDataColumn targetColumn = columns.get(recommendation.getTargetColumnId());
        if (!validRelationshipEndpoint(sourceTable, sourceColumn, sourceProfile)
            || !validRelationshipEndpoint(targetTable, targetColumn, targetProfile)) {
            throw conflict("关系建议的画像或端点已失效");
        }
        String sourceStructureHash = DataTableProfiler.structureHash(
            sourceTable, activeColumns(sourceTable.getId(), columns.values())
        );
        String targetStructureHash = DataTableProfiler.structureHash(
            targetTable, activeColumns(targetTable.getId(), columns.values())
        );
        if (!Objects.equals(sourceProfile.getSourceHash(), sourceStructureHash)
            || !Objects.equals(targetProfile.getSourceHash(), targetStructureHash)) {
            throw conflict("关系建议的端点结构已变化，请重新画像");
        }
        return new RelationshipImportProposalView(
            recommendation.getId(), recommendation.getSourceTableId(),
            recommendation.getSourceColumnId(), recommendation.getTargetTableId(),
            recommendation.getTargetColumnId(),
            sourceProfile.getId(), sourceProfile.getRevisionNo(), sourceStructureHash,
            targetProfile.getId(), targetProfile.getRevisionNo(), targetStructureHash,
            tableStateHash(sourceTable), columnStateHash(sourceColumn),
            tableStateHash(targetTable), columnStateHash(targetColumn),
            recommendation.getJoinType(), recommendation.getJoinCondition(), recommendation.getReason()
        );
    }

    private boolean validRelationshipEndpoint(
        AgentDataTable table,
        AgentDataColumn column,
        AgentDataTableProfile profile
    ) {
        return table != null && column != null && profile != null
            && table.getId().equals(column.getTableId())
            && table.getId().equals(profile.getTableId())
            && "active".equals(table.getStatus())
            && Boolean.TRUE.equals(table.getMetadataPresent())
            && "active".equals(column.getStatus())
            && Boolean.TRUE.equals(column.getMetadataPresent())
            && !Boolean.TRUE.equals(profile.getIgnored());
    }

    private List<AgentDataColumn> activeColumns(
        Long tableId,
        Collection<AgentDataColumn> columns
    ) {
        return columns.stream()
            .filter(column -> tableId.equals(column.getTableId()))
            .filter(column -> "active".equals(column.getStatus()))
            .filter(column -> Boolean.TRUE.equals(column.getMetadataPresent()))
            .sorted(Comparator.comparing(AgentDataColumn::getId))
            .toList();
    }

    private TableImportProposalView tableProposal(
        AgentDataTable table,
        AgentDataTableProfile profile,
        Map<Long, AgentDataColumn> columns
    ) {
        List<ColumnProfileView> semanticColumns = jsonMapper.readValue(
            profile.getColumnsProfileJson(), COLUMN_PROFILES
        );
        List<AgentDataColumn> currentColumns = columns.values().stream()
            .filter(column -> table.getId().equals(column.getTableId()))
            .filter(column -> "active".equals(column.getStatus()))
            .filter(column -> Boolean.TRUE.equals(column.getMetadataPresent()))
            .sorted(Comparator.comparing(AgentDataColumn::getId))
            .toList();
        String sourceHash = DataTableProfiler.structureHash(table, currentColumns);
        if (!sourceHash.equals(profile.getSourceHash())) {
            throw conflict("表结构在画像后已变化，请重新画像再生成智能导入预览");
        }
        Set<Long> semanticIds = semanticColumns.stream()
            .map(ColumnProfileView::columnId).collect(Collectors.toSet());
        Set<Long> currentIds = currentColumns.stream()
            .map(AgentDataColumn::getId).collect(Collectors.toSet());
        if (semanticIds.size() != semanticColumns.size() || !semanticIds.equals(currentIds)) {
            throw conflict("表画像字段集合与当前元数据不一致");
        }
        List<ColumnImportProposalView> updates = new ArrayList<>();
        for (ColumnProfileView semantic : semanticColumns) {
            AgentDataColumn current = columns.get(semantic.columnId());
            if (current == null || !table.getId().equals(current.getTableId())) {
                throw conflict("表画像引用的字段已失效");
            }
            updates.add(new ColumnImportProposalView(
                current.getId(), columnSnapshot(current),
                semantic.term(), semantic.description(),
                Boolean.TRUE.equals(current.getIsSensitive()) || semantic.sensitive(),
                current.getStatus()
            ));
        }
        return new TableImportProposalView(
            profile.getId(), profile.getRevisionNo(), table.getId(),
            sourceHash, table.getPhysicalSchema(), table.getPhysicalName(), tableSnapshot(table),
            profile.getTerm(), profile.getDescription(), table.getStatus(), updates
        );
    }

    private TableGovernanceSnapshotView tableSnapshot(AgentDataTable table) {
        return new TableGovernanceSnapshotView(
            table.getDisplayName(), table.getDescription(), table.getStatus(),
            Boolean.TRUE.equals(table.getMetadataPresent()), tableStateHash(table)
        );
    }

    private ColumnGovernanceSnapshotView columnSnapshot(AgentDataColumn column) {
        return new ColumnGovernanceSnapshotView(
            column.getId(), column.getDisplayName(), column.getDescription(),
            Boolean.TRUE.equals(column.getIsSensitive()), column.getStatus(),
            Boolean.TRUE.equals(column.getMetadataPresent()), columnStateHash(column)
        );
    }

    private void requireTableSnapshot(
        AgentDataTable current,
        TableGovernanceSnapshotView expected
    ) {
        if (expected == null
            || !Objects.equals(expected.displayName(), current.getDisplayName())
            || !Objects.equals(expected.description(), current.getDescription())
            || !Objects.equals(expected.status(), current.getStatus())
            || expected.metadataPresent() != Boolean.TRUE.equals(current.getMetadataPresent())
            || !Objects.equals(expected.stateHash(), tableStateHash(current))) {
            throw conflict("数据表治理配置在预览生成后已变化，请重新生成预览");
        }
    }

    private void requireColumnSnapshot(
        AgentDataColumn current,
        ColumnGovernanceSnapshotView expected
    ) {
        if (expected == null || !current.getId().equals(expected.columnId())
            || !Objects.equals(expected.displayName(), current.getDisplayName())
            || !Objects.equals(expected.description(), current.getDescription())
            || expected.sensitive() != Boolean.TRUE.equals(current.getIsSensitive())
            || !Objects.equals(expected.status(), current.getStatus())
            || expected.metadataPresent() != Boolean.TRUE.equals(current.getMetadataPresent())
            || !Objects.equals(expected.stateHash(), columnStateHash(current))) {
            throw conflict("数据字段治理配置在预览生成后已变化，请重新生成预览");
        }
    }

    private String tableStateHash(AgentDataTable table) {
        return ContentHashing.sha256(canonicalJson(tableAudit(table)));
    }

    private String columnStateHash(AgentDataColumn column) {
        return ContentHashing.sha256(canonicalJson(columnAudit(column)));
    }

    private Map<String, Object> tableAudit(AgentDataTable table) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", table.getId());
        value.put("datasetId", table.getDatasetId());
        value.put("physicalSchema", table.getPhysicalSchema());
        value.put("physicalName", table.getPhysicalName());
        value.put("tableType", table.getTableType());
        value.put("displayName", table.getDisplayName());
        value.put("description", table.getDescription());
        value.put("status", table.getStatus());
        value.put("metadataPresent", table.getMetadataPresent());
        value.put("updateTime", table.getUpdateTime());
        return value;
    }

    private Map<String, Object> columnAudit(AgentDataColumn column) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", column.getId());
        value.put("tableId", column.getTableId());
        value.put("physicalName", column.getPhysicalName());
        value.put("dataType", column.getDataType());
        value.put("displayName", column.getDisplayName());
        value.put("description", column.getDescription());
        value.put("primary", column.getIsPrimary());
        value.put("sensitive", column.getIsSensitive());
        value.put("status", column.getStatus());
        value.put("metadataPresent", column.getMetadataPresent());
        value.put("updatedAt", column.getUpdatedAt());
        return value;
    }

    private void insertItem(
        Long previewId,
        String itemType,
        Long resourceId,
        Object proposal,
        LocalDateTime now
    ) {
        String proposedJson = canonicalJson(proposal);
        AgentDataSmartImportItem item = new AgentDataSmartImportItem();
        item.setId(idGenerator.nextId());
        item.setPreviewId(previewId);
        item.setItemType(itemType);
        item.setResourceId(resourceId);
        item.setContentHash(ContentHashing.sha256(proposedJson));
        item.setProposedJson(proposedJson);
        item.setStatus("available");
        item.setCreatedAt(now);
        if (mapper.insertPreviewItem(item) != 1) {
            throw conflict("智能导入预览项创建失败");
        }
    }

    private SmartImportPreviewView previewView(
        AgentDataSmartImportPreview preview,
        List<AgentDataSmartImportItem> items
    ) {
        return new SmartImportPreviewView(
            preview.getId(), preview.getDatasetId(), preview.getProfileJobId(), preview.getStatus(),
            preview.getDatasetRevision(), preview.getRevisionNo(), preview.getExpiresAt(),
            preview.getCreatedBy(), preview.getCreatedAt(), preview.getAppliedBy(), preview.getAppliedAt(),
            items.stream().map(this::itemView).toList()
        );
    }

    private SmartImportItemView itemView(AgentDataSmartImportItem item) {
        TableImportProposalView table = "table".equals(item.getItemType())
            ? jsonMapper.readValue(item.getProposedJson(), TableImportProposalView.class) : null;
        RelationshipImportProposalView relationship = "relationship".equals(item.getItemType())
            ? jsonMapper.readValue(item.getProposedJson(), RelationshipImportProposalView.class) : null;
        return new SmartImportItemView(
            item.getId(), item.getItemType(), item.getResourceId(), item.getStatus(),
            item.getContentHash(), table, relationship, item.getAppliedResourceId(),
            item.getErrorMessage()
        );
    }

    private AgentDataDataset authorize(Long datasetId, CurrentPrincipal principal, String action) {
        AgentDataDataset dataset = catalogService.requireDataset(datasetId);
        authorizationEnforcer.requireAllowed(principal, catalogService.datasetContext(dataset, action));
        return dataset;
    }

    private AgentDataDataset activeAccess(Long datasetId, CurrentPrincipal principal, String action) {
        AgentDataDataset dataset = authorize(datasetId, principal, action);
        AgentDataSource source = catalogService.requireSource(dataset.getDataSourceId());
        if (!"active".equals(dataset.getStatus()) || !"active".equals(source.getStatus())) {
            throw conflict("只有活动数据集和数据源可以创建或应用智能导入");
        }
        return dataset;
    }

    private void verifyHash(AgentDataSmartImportItem item) {
        String current = ContentHashing.sha256(canonicalJson(
            jsonMapper.readValue(item.getProposedJson(), Object.class)
        ));
        if (!current.equals(item.getContentHash())) {
            throw conflict("智能导入预览内容校验失败，请重新生成预览");
        }
    }

    private String canonicalJson(Object value) {
        Object source = value instanceof String text
            ? jsonMapper.readValue(text, Object.class) : jsonMapper.readValue(
                jsonMapper.writeValueAsString(value), Object.class
            );
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
        if (governanceMapper.insertChange(row) != 1) {
            throw conflict("智能导入审计记录写入失败");
        }
    }

    private ServiceException notFound(String message) {
        return new ServiceException(message, HttpStatus.NOT_FOUND);
    }

    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    private record LockedEndpoint(
        AgentDataTable table,
        Map<Long, AgentDataColumn> columns,
        List<AgentDataColumn> activeColumns
    ) {
    }
}
