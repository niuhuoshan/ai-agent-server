package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataProfileJob;
import group.aitools.nhs.platform.data.domain.AgentDataProfileJobTable;
import group.aitools.nhs.platform.data.domain.AgentDataProfileRelationRecommendation;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.data.domain.AgentDataTableProfile;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.mapper.DataGovernanceMapper;
import group.aitools.nhs.platform.data.mapper.DataProfileMapper;
import group.aitools.nhs.platform.data.persistence.row.MetadataChangeRow;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.ColumnProfileView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.CreateProfileJobRequest;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.ProfileJobDetailView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.ProfileJobTableView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.ProfileJobView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.ProfileTagStatView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.RelationRecommendationView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.SampleRowView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.TableProfileDetailView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.TableProfilePageView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.TableProfileStatsView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.TableProfileSummaryView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.UpdateProfileIgnoreRequest;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** User-facing orchestration and query service for metadata profiling. */
@Service
public class DataProfileApplicationService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<ColumnProfileView>> COLUMN_PROFILES = new TypeReference<>() {
    };
    private static final TypeReference<List<SampleRowView>> SAMPLE_ROWS = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final DataSourceCatalogService catalogService;
    private final DataCatalogMapper catalogMapper;
    private final DataProfileMapper mapper;
    private final DataGovernanceMapper governanceMapper;
    private final JsonMapper jsonMapper;

    public DataProfileApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        DataSourceCatalogService catalogService,
        DataCatalogMapper catalogMapper,
        DataProfileMapper mapper,
        DataGovernanceMapper governanceMapper,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.catalogService = catalogService;
        this.catalogMapper = catalogMapper;
        this.mapper = mapper;
        this.governanceMapper = governanceMapper;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProfileJobView createJob(Long datasetId, CreateProfileJobRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        Access access = activeAccess(datasetId, principal, "sync");
        return createJob(access, request.mode(), request.tableIds(), null, principal.id(), true);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProfileJobView resumeJob(Long datasetId, Long jobId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        Access access = activeAccess(datasetId, principal, "sync");
        AgentDataProfileJob previous = mapper.selectJobForUpdate(datasetId, jobId);
        if (previous == null) {
            throw notFound("画像任务不存在");
        }
        if (!Set.of("failed", "cancelled").contains(previous.getStatus())) {
            throw conflict("只有失败或已取消的画像任务可以续跑");
        }
        List<Long> tableIds = mapper.selectResumableTableIds(datasetId, jobId);
        if (tableIds.isEmpty() && previous.getTotalTables() != null && previous.getTotalTables() > 0) {
            // A failure after all table profiles completed (for example while deriving
            // relationships) still needs a durable recovery path.
            tableIds = mapper.selectJobTables(datasetId, jobId).stream()
                .map(AgentDataProfileJobTable::getTableId)
                .toList();
        }
        if (tableIds.isEmpty()) {
            throw conflict("画像任务没有可续跑的数据表");
        }
        return createJob(access, "incremental", tableIds, jobId, principal.id(), false);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProfileJobView cancelJob(Long datasetId, Long jobId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        access(datasetId, principal, "sync");
        AgentDataProfileJob job = mapper.selectJobForUpdate(datasetId, jobId);
        if (job == null) {
            throw notFound("画像任务不存在");
        }
        if ("queued".equals(job.getStatus())) {
            if (mapper.cancelQueuedJob(datasetId, jobId, job.getRevisionNo(), LocalDateTime.now()) != 1) {
                throw conflict("画像任务状态已变化，请刷新后重试");
            }
        } else if ("running".equals(job.getStatus())) {
            if (!Boolean.TRUE.equals(job.getCancelRequested()) && mapper.requestRunningCancel(
                datasetId, jobId, job.getRevisionNo(), LocalDateTime.now()
            ) != 1) {
                throw conflict("画像任务状态已变化，请刷新后重试");
            }
        } else if (!"cancelled".equals(job.getStatus())) {
            throw conflict("已结束的画像任务不能取消");
        }
        return jobView(requireJob(datasetId, jobId));
    }

    public List<ProfileJobView> jobs(Long datasetId, int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        access(datasetId, principal, "read");
        return mapper.selectJobs(datasetId, limit).stream().map(this::jobView).toList();
    }

    public ProfileJobDetailView job(Long datasetId, Long jobId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        access(datasetId, principal, "read");
        AgentDataProfileJob job = requireJob(datasetId, jobId);
        Map<Long, AgentDataTable> tables = tableMap(datasetId);
        List<ProfileJobTableView> items = mapper.selectJobTables(datasetId, jobId).stream()
            .map(item -> jobTableView(item, tables.get(item.getTableId())))
            .toList();
        return new ProfileJobDetailView(jobView(job), items);
    }

    public TableProfilePageView profiles(
        Long datasetId,
        int page,
        int pageSize,
        String query,
        String tag,
        Boolean ignored,
        String classification,
        String status,
        String sortBy,
        String sortOrder
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        access(datasetId, principal, "read");
        Map<Long, AgentDataTableProfile> profiles = mapper.selectLatestProfiles(datasetId).stream()
            .collect(Collectors.toMap(AgentDataTableProfile::getTableId, Function.identity()));
        Map<Long, AgentDataProfileJobTable> executions = mapper.selectLatestJobTables(datasetId).stream()
            .collect(Collectors.toMap(AgentDataProfileJobTable::getTableId, Function.identity()));
        String normalizedQuery = trimToNull(query);
        String normalizedTag = trimToNull(tag);
        String normalizedClassification = normalizeClassification(classification);
        String normalizedStatus = normalizeTableStatusFilter(status);
        List<TableProfileSummaryView> result = new ArrayList<>();
        for (AgentDataTable table : catalogMapper.selectTables(datasetId)) {
            AgentDataTableProfile profile = profiles.get(table.getId());
            AgentDataProfileJobTable execution = executions.get(table.getId());
            TableProfileSummaryView view = summary(table, profile, execution);
            if (!matches(
                view, normalizedQuery, normalizedTag, ignored,
                normalizedClassification, normalizedStatus
            )) {
                continue;
            }
            result.add(view);
        }
        result.sort(profileComparator(sortBy, sortOrder));
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.min(200, Math.max(1, pageSize));
        int from = Math.min(result.size(), (normalizedPage - 1) * normalizedSize);
        int to = Math.min(result.size(), from + normalizedSize);
        int pages = result.isEmpty() ? 0 : (result.size() + normalizedSize - 1) / normalizedSize;
        return new TableProfilePageView(
            result.subList(from, to), result.size(), normalizedPage, normalizedSize, pages
        );
    }

    public TableProfileStatsView stats(Long datasetId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        access(datasetId, principal, "read");
        List<AgentDataTableProfile> profiles = mapper.selectLatestProfiles(datasetId);
        Map<Long, AgentDataTable> tables = tableMap(datasetId);
        long tableCount = 0;
        long viewCount = 0;
        long ignoredCount = 0;
        long temporaryCount = 0;
        BigDecimal confidence = BigDecimal.ZERO;
        LocalDateTime latest = null;
        Map<String, Long> tags = new HashMap<>();
        for (AgentDataTableProfile profile : profiles) {
            AgentDataTable table = tables.get(profile.getTableId());
            if (table != null && table.getTableType() != null
                && table.getTableType().toLowerCase(Locale.ROOT).contains("view")) {
                viewCount++;
            } else {
                tableCount++;
            }
            ignoredCount += Boolean.TRUE.equals(profile.getIgnored()) ? 1 : 0;
            temporaryCount += "business".equals(profile.getTemporaryClassification()) ? 0 : 1;
            confidence = confidence.add(profile.getConfidenceScore());
            if (latest == null || profile.getCreatedAt().isAfter(latest)) {
                latest = profile.getCreatedAt();
            }
            for (String value : strings(profile.getTagsJson())) {
                tags.merge(value, 1L, Long::sum);
            }
        }
        BigDecimal average = profiles.isEmpty() ? BigDecimal.ZERO
            : confidence.divide(BigDecimal.valueOf(profiles.size()), 2, RoundingMode.HALF_UP);
        List<ProfileTagStatView> tagViews = tags.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
            .map(entry -> new ProfileTagStatView(entry.getKey(), entry.getValue()))
            .toList();
        return new TableProfileStatsView(
            profiles.size(), tableCount, viewCount, ignoredCount, temporaryCount,
            average, latest, tagViews
        );
    }

    public TableProfileDetailView profile(Long datasetId, Long tableId, int relatedLimit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        access(datasetId, principal, "read");
        AgentDataTable table = requireTable(datasetId, tableId);
        AgentDataTableProfile profile = mapper.selectLatestProfile(datasetId, tableId);
        if (profile == null) {
            throw notFound("数据表尚无成功画像");
        }
        List<RelationRecommendationView> related = recommendations(
            datasetId, profile.getJobId(), tableId, relatedLimit
        );
        return detail(table, profile, related);
    }

    public List<RelationRecommendationView> related(Long datasetId, Long tableId, int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        access(datasetId, principal, "read");
        AgentDataTableProfile profile = mapper.selectLatestProfile(datasetId, tableId);
        if (profile == null) {
            throw notFound("数据表尚无成功画像");
        }
        return recommendations(datasetId, profile.getJobId(), tableId, limit);
    }

    @Transactional(rollbackFor = Exception.class)
    public TableProfileDetailView updateIgnore(
        Long datasetId,
        Long tableId,
        UpdateProfileIgnoreRequest request
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        access(datasetId, principal, "update");
        AgentDataTable table = requireTable(datasetId, tableId);
        AgentDataTableProfile before = mapper.selectLatestProfile(datasetId, tableId);
        if (before == null) {
            throw notFound("数据表尚无成功画像");
        }
        LocalDateTime now = LocalDateTime.now();
        if (mapper.updateProfileIgnore(
            datasetId, tableId, before.getId(), request.revisionNo(), request.ignored(),
            principal.id(), now
        ) != 1) {
            throw conflict("表画像忽略决定已变化，请刷新后重试");
        }
        AgentDataTableProfile after = mapper.selectLatestProfile(datasetId, tableId);
        change(
            datasetId, "table_profile", after.getId(), "update",
            ignoreSnapshot(before), ignoreSnapshot(after), principal.id(), now
        );
        return detail(
            table, after, recommendations(datasetId, after.getJobId(), tableId, 15)
        );
    }

    static String externalJobStatus(String internal) {
        return switch (internal) {
            case "succeeded" -> "done";
            case "failed" -> "error";
            default -> internal;
        };
    }

    static String externalTableStatus(String internal) {
        return "succeeded".equals(internal) ? "success" : internal;
    }

    private ProfileJobView createJob(
        Access access,
        String mode,
        List<Long> requestedIds,
        Long resumeOfJobId,
        Long actorId,
        boolean applyIncrementalFilter
    ) {
        if (mapper.countActiveJobs(access.dataset().getId()) > 0) {
            throw conflict("当前数据集已有排队或运行中的画像任务");
        }
        Map<Long, List<AgentDataColumn>> columns = columnMap(access.dataset().getId());
        List<AgentDataTable> selected = selectTables(
            access.dataset().getId(), requestedIds, columns
        );
        Map<Long, AgentDataTableProfile> latest = mapper.selectLatestProfiles(access.dataset().getId()).stream()
            .collect(Collectors.toMap(AgentDataTableProfile::getTableId, Function.identity()));
        if ("incremental".equals(mode) && applyIncrementalFilter) {
            selected = selected.stream().filter(table -> {
                AgentDataTableProfile current = latest.get(table.getId());
                return current == null || !current.getSourceHash().equals(
                    DataTableProfiler.structureHash(table, columns.getOrDefault(table.getId(), List.of()))
                );
            }).toList();
        }
        LocalDateTime now = LocalDateTime.now();
        AgentDataProfileJob job = new AgentDataProfileJob();
        job.setId(idGenerator.nextId());
        job.setDatasetId(access.dataset().getId());
        job.setDataSourceId(access.source().getId());
        job.setMode(mode);
        job.setStatus(selected.isEmpty() ? "succeeded" : "queued");
        job.setRequestedTableIdsJson(jsonMapper.writeValueAsString(
            selected.stream().map(AgentDataTable::getId).toList()
        ));
        job.setDatasetRevision(access.dataset().getRevisionNo());
        job.setDataSourceRevision(access.source().getRevisionNo());
        job.setTotalTables(selected.size());
        job.setCompletedTables(0);
        job.setFailedTables(0);
        job.setProgressPercent(selected.isEmpty() ? BigDecimal.valueOf(100) : BigDecimal.ZERO);
        job.setCancelRequested(false);
        job.setResumeOfJobId(resumeOfJobId);
        job.setAttemptNo(0);
        job.setMaxAttempts(5);
        job.setRevisionNo(1);
        job.setRequestedBy(actorId);
        job.setCreatedAt(now);
        job.setStartedAt(selected.isEmpty() ? now : null);
        job.setFinishedAt(selected.isEmpty() ? now : null);
        job.setUpdatedAt(now);
        try {
            if (mapper.insertJob(job) != 1) {
                throw conflict("画像任务创建失败");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("当前数据集已有排队或运行中的画像任务");
        }
        int sequence = 0;
        for (AgentDataTable table : selected) {
            AgentDataProfileJobTable item = new AgentDataProfileJobTable();
            item.setId(idGenerator.nextId());
            item.setJobId(job.getId());
            item.setDatasetId(job.getDatasetId());
            item.setTableId(table.getId());
            item.setSequenceNo(++sequence);
            item.setSourceHash(DataTableProfiler.structureHash(
                table, columns.getOrDefault(table.getId(), List.of())
            ));
            item.setStatus("pending");
            item.setAttemptNo(0);
            item.setUpdatedAt(now);
            if (mapper.insertJobTable(item) != 1) {
                throw conflict("画像表级任务创建失败");
            }
        }
        change(
            job.getDatasetId(), "profile_job", job.getId(), "create", null,
            jobAudit(job), actorId, now
        );
        return jobView(job);
    }

    private Access access(Long datasetId, CurrentPrincipal principal, String action) {
        AgentDataDataset dataset = catalogService.requireDataset(datasetId);
        authorizationEnforcer.requireAllowed(principal, catalogService.datasetContext(dataset, action));
        AgentDataSource source = catalogService.requireSource(dataset.getDataSourceId());
        return new Access(dataset, source);
    }

    private Access activeAccess(Long datasetId, CurrentPrincipal principal, String action) {
        Access access = access(datasetId, principal, action);
        AgentDataDataset dataset = access.dataset();
        AgentDataSource source = access.source();
        if (!"active".equals(dataset.getStatus()) || !"active".equals(source.getStatus())) {
            throw conflict("只有活动数据集和数据源可以执行元数据画像");
        }
        return access;
    }

    private List<AgentDataTable> selectTables(
        Long datasetId,
        List<Long> requestedIds,
        Map<Long, List<AgentDataColumn>> columns
    ) {
        List<AgentDataTable> available = catalogMapper.selectTables(datasetId).stream()
            .filter(table -> "active".equals(table.getStatus()))
            .filter(table -> Boolean.TRUE.equals(table.getMetadataPresent()))
            .filter(table -> !columns.getOrDefault(table.getId(), List.of()).isEmpty())
            .sorted(Comparator.comparing(
                    AgentDataTable::getPhysicalSchema,
                    Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER)
                )
                .thenComparing(AgentDataTable::getPhysicalName)
                .thenComparing(AgentDataTable::getId))
            .toList();
        if (requestedIds == null || requestedIds.isEmpty()) {
            return available;
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>(requestedIds);
        Map<Long, AgentDataTable> byId = available.stream()
            .collect(Collectors.toMap(AgentDataTable::getId, Function.identity()));
        List<Long> invalid = normalized.stream().filter(id -> !byId.containsKey(id)).toList();
        if (!invalid.isEmpty()) {
            throw new ServiceException("画像任务包含不存在或不可用的数据表：" + invalid, HttpStatus.BAD_REQUEST);
        }
        return normalized.stream().map(byId::get).toList();
    }

    private boolean matches(
        TableProfileSummaryView view,
        String query,
        String tag,
        Boolean ignored,
        String classification,
        String status
    ) {
        if (query != null) {
            String needle = query.toLowerCase(Locale.ROOT);
            boolean matchedText = Stream.of(
                    view.tableName(), view.displayName(), view.term(), view.description()
                )
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains(needle));
            boolean matchedTag = view.tags().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains(needle));
            if (!matchedText && !matchedTag) {
                return false;
            }
        }
        if (tag != null && !view.tags().contains(tag)) {
            return false;
        }
        if (ignored != null && !Objects.equals(ignored, view.ignored())) {
            return false;
        }
        if (classification != null
            && !classification.equals(view.temporaryClassification())) {
            return false;
        }
        return status == null || status.equals(view.status());
    }

    private Comparator<TableProfileSummaryView> profileComparator(String sortBy, String sortOrder) {
        boolean descending = !"asc".equalsIgnoreCase(sortOrder);
        String key = sortBy == null ? "default" : sortBy.strip().toLowerCase(Locale.ROOT);
        Comparator<String> textOrder = descending
            ? String.CASE_INSENSITIVE_ORDER.reversed() : String.CASE_INSENSITIVE_ORDER;
        Comparator<LocalDateTime> timeOrder = descending
            ? Comparator.reverseOrder() : Comparator.naturalOrder();
        Comparator<BigDecimal> decimalOrder = descending
            ? Comparator.reverseOrder() : Comparator.naturalOrder();
        Comparator<TableProfileSummaryView> comparator = switch (key) {
            case "name", "table_name" -> Comparator.comparing(
                TableProfileSummaryView::tableName,
                Comparator.nullsLast(textOrder)
            );
            case "term" -> Comparator.comparing(
                TableProfileSummaryView::term,
                Comparator.nullsLast(textOrder)
            );
            case "created", "created_at" -> Comparator.comparing(
                TableProfileSummaryView::createdAt,
                Comparator.nullsLast(timeOrder)
            );
            case "default", "confidence", "confidence_score" -> Comparator.comparing(
                TableProfileSummaryView::confidenceScore,
                Comparator.nullsLast(decimalOrder)
            );
            default -> throw new ServiceException("不支持的画像排序字段", HttpStatus.BAD_REQUEST);
        };
        return comparator.thenComparing(TableProfileSummaryView::tableName, String.CASE_INSENSITIVE_ORDER);
    }

    private TableProfileSummaryView summary(
        AgentDataTable table,
        AgentDataTableProfile profile,
        AgentDataProfileJobTable execution
    ) {
        String status = execution == null
            ? (profile == null ? "pending" : "success")
            : externalTableStatus(execution.getStatus());
        return new TableProfileSummaryView(
            profile == null ? null : profile.getId(), table.getDatasetId(), table.getId(),
            profile == null ? null : profile.getJobId(), table.getPhysicalSchema(),
            table.getPhysicalName(), table.getDisplayName(), profile == null ? null : profile.getTerm(),
            profile == null ? null : profile.getDescription(), table.getTableType(), status,
            profile == null ? null : profile.getColumnCount(),
            profile == null ? null : profile.getSampleRowCount(),
            profile == null ? null : profile.getConfidenceScore(),
            profile == null ? null : profile.getConfidenceReason(),
            profile == null ? List.of() : strings(profile.getTagsJson()),
            profile == null ? null : profile.getTemporaryClassification(),
            profile == null ? null : profile.getIgnored(),
            profile == null ? null : profile.getIgnoreDecision(),
            profile == null ? null : profile.getRevisionNo(),
            profile == null ? null : profile.getCreatedAt(),
            profile == null ? null : profile.getUpdatedAt()
        );
    }

    private TableProfileDetailView detail(
        AgentDataTable table,
        AgentDataTableProfile profile,
        List<RelationRecommendationView> related
    ) {
        return new TableProfileDetailView(
            summary(table, profile, null), profile.getDdlText(), profile.getRowCountEstimate(),
            Boolean.TRUE.equals(profile.getSampleRedacted()),
            jsonMapper.readValue(profile.getColumnsProfileJson(), COLUMN_PROFILES),
            jsonMapper.readValue(profile.getSampleDataJson(), SAMPLE_ROWS), related
        );
    }

    private List<RelationRecommendationView> recommendations(
        Long datasetId,
        Long jobId,
        Long tableId,
        int limit
    ) {
        Map<Long, AgentDataTable> tables = tableMap(datasetId);
        Map<Long, AgentDataColumn> columns = catalogMapper.selectColumns(datasetId).stream()
            .collect(Collectors.toMap(AgentDataColumn::getId, Function.identity()));
        return mapper.selectTableRecommendations(
            datasetId, jobId, tableId, Math.min(30, Math.max(1, limit))
        ).stream().map(item -> recommendationView(item, tables, columns)).toList();
    }

    private RelationRecommendationView recommendationView(
        AgentDataProfileRelationRecommendation item,
        Map<Long, AgentDataTable> tables,
        Map<Long, AgentDataColumn> columns
    ) {
        AgentDataTable source = tables.get(item.getSourceTableId());
        AgentDataTable target = tables.get(item.getTargetTableId());
        AgentDataColumn sourceColumn = columns.get(item.getSourceColumnId());
        AgentDataColumn targetColumn = columns.get(item.getTargetColumnId());
        return new RelationRecommendationView(
            item.getId(), item.getDatasetId(), item.getProfileJobId(),
            item.getSourceTableId(), physicalName(source), item.getSourceColumnId(),
            sourceColumn == null ? null : sourceColumn.getPhysicalName(),
            item.getTargetTableId(), physicalName(target), item.getTargetColumnId(),
            targetColumn == null ? null : targetColumn.getPhysicalName(),
            item.getConfidenceScore(), item.getJoinType(), item.getJoinCondition(),
            item.getReason(), item.getStatus()
        );
    }

    private ProfileJobView jobView(AgentDataProfileJob job) {
        return new ProfileJobView(
            job.getId(), job.getDatasetId(), job.getDataSourceId(), job.getMode(),
            externalJobStatus(job.getStatus()), job.getTotalTables(), job.getCompletedTables(),
            job.getFailedTables(), job.getProgressPercent(), job.getCurrentTableId(),
            Boolean.TRUE.equals(job.getCancelRequested()), job.getResumeOfJobId(),
            job.getAttemptNo(), job.getMaxAttempts(), job.getRevisionNo(), job.getErrorMessage(),
            job.getRequestedBy(), job.getCreatedAt(), job.getStartedAt(), job.getFinishedAt(),
            job.getUpdatedAt()
        );
    }

    private ProfileJobTableView jobTableView(AgentDataProfileJobTable item, AgentDataTable table) {
        return new ProfileJobTableView(
            item.getId(), item.getJobId(), item.getTableId(),
            table == null ? null : table.getPhysicalSchema(),
            table == null ? null : table.getPhysicalName(), externalTableStatus(item.getStatus()),
            item.getSequenceNo(), item.getAttemptNo(), item.getProfileId(), item.getErrorMessage(),
            item.getStartedAt(), item.getFinishedAt(), item.getUpdatedAt()
        );
    }

    private Map<Long, AgentDataTable> tableMap(Long datasetId) {
        return catalogMapper.selectTables(datasetId).stream()
            .collect(Collectors.toMap(AgentDataTable::getId, Function.identity()));
    }

    private Map<Long, List<AgentDataColumn>> columnMap(Long datasetId) {
        Map<Long, List<AgentDataColumn>> result = new LinkedHashMap<>();
        for (AgentDataColumn column : catalogMapper.selectColumns(datasetId)) {
            if ("active".equals(column.getStatus()) && Boolean.TRUE.equals(column.getMetadataPresent())) {
                result.computeIfAbsent(column.getTableId(), ignored -> new ArrayList<>()).add(column);
            }
        }
        return result;
    }

    private AgentDataProfileJob requireJob(Long datasetId, Long jobId) {
        AgentDataProfileJob job = mapper.selectJob(datasetId, jobId);
        if (job == null) {
            throw notFound("画像任务不存在");
        }
        return job;
    }

    private AgentDataTable requireTable(Long datasetId, Long tableId) {
        AgentDataTable table = catalogMapper.selectTable(tableId);
        if (table == null || !datasetId.equals(table.getDatasetId()) || !"0".equals(table.getDelFlag())) {
            throw notFound("数据表不存在");
        }
        return table;
    }

    private List<String> strings(String value) {
        return value == null || value.isBlank()
            ? List.of() : jsonMapper.readValue(value, STRING_LIST);
    }

    private String normalizeTableStatusFilter(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        if (!Set.of("pending", "running", "success", "failed").contains(normalized)) {
            throw new ServiceException("画像表状态筛选无效", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private String normalizeClassification(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        if (!Set.of("business", "temporary", "backup", "staging", "system").contains(normalized)) {
            throw new ServiceException("画像表分类筛选无效", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private String physicalName(AgentDataTable table) {
        if (table == null) {
            return null;
        }
        return table.getPhysicalSchema() == null || table.getPhysicalSchema().isBlank()
            ? table.getPhysicalName()
            : table.getPhysicalSchema() + "." + table.getPhysicalName();
    }

    private Map<String, Object> ignoreSnapshot(AgentDataTableProfile profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("profileId", profile.getId());
        value.put("tableId", profile.getTableId());
        value.put("ignored", profile.getIgnored());
        value.put("ignoreDecision", profile.getIgnoreDecision());
        value.put("revisionNo", profile.getRevisionNo());
        return value;
    }

    private Map<String, Object> jobAudit(AgentDataProfileJob job) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("jobId", job.getId());
        value.put("mode", job.getMode());
        value.put("status", externalJobStatus(job.getStatus()));
        value.put("totalTables", job.getTotalTables());
        value.put("datasetRevision", job.getDatasetRevision());
        value.put("dataSourceRevision", job.getDataSourceRevision());
        value.put("resumeOfJobId", job.getResumeOfJobId());
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
            throw conflict("元数据画像审计记录写入失败");
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private ServiceException notFound(String message) {
        return new ServiceException(message, HttpStatus.NOT_FOUND);
    }

    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    private record Access(AgentDataDataset dataset, AgentDataSource source) {
    }
}
