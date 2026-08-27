package group.aitools.nhs.platform.nhs.portal.example;

import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataQuery;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.service.ReadOnlySqlValidator;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;
import group.aitools.nhs.platform.conversation.service.ConversationFeedbackCandidateRecorder;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.nhs.portal.chatbi.PortalChatBIModelGateway;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 负责门户Example相关的业务编排与领域规则处理。
 *
 * Durable ChatBI/Few-shot examples owned by the local installation.
 *
 * <p>The relational row is the source of truth.  A successful "sync" means the
 * row passed the platform SQL policy and is eligible for the local relational
 * retrieval path; it never claims that an optional Redis, embedding or RAGFlow
 * provider was contacted.</p>
 */
@Service
public class PortalExampleService implements ConversationFeedbackCandidateRecorder {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int SYNC_PAGE_SIZE = 200;
    private static final Set<String> REVIEW_STATUSES = Set.of(
        "pending", "approved", "rejected", "deprecated"
    );
    private static final Set<String> SYNCABLE_STATUSES = Set.of("approved", "deprecated");
    private static final Set<String> CATEGORIES = Set.of("general", "knowledge", "data_query");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String ENHANCE_SYSTEM_PROMPT = """
        你是企业数据分析案例整理助手。请把输入案例整理成可复用的独立案例。
        只输出一个 JSON 对象，不要输出 Markdown 或解释。JSON 必须包含：
        refined_query：脱离上下文也能理解的完整问题，不得添加输入中没有的业务事实；
        context_summary：简洁说明业务背景和必要上下文；
        sql_metadata：对象，包含 tables、query_type、dimensions，其中 tables 和 dimensions 为字符串数组。
        """;

    private final CurrentPrincipalProvider principalProvider;
    private final AgentChatBIExampleMapper mapper;
    private final DataCatalogMapper catalogMapper;
    private final ReadOnlySqlValidator sqlValidator;
    private final JsonMapper jsonMapper;
    private final PlatformIdGenerator idGenerator;
    private final PortalChatBIModelGateway modelGateway;
    private final AgentChatBIExampleRevisionMapper revisionMapper;
    private final PortalExampleAuditService auditService;

    /**
     * 创建 {@code PortalExampleService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param mapper {@code mapper}参数
     * @param catalogMapper 目录Mapper参数
     * @param sqlValidator {@code sqlValidator}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param idGenerator {@code idGenerator}参数
     * @param modelGateway 模型Gateway参数
     * @param revisionMapper {@code revisionMapper}参数
     * @param auditService 审计Service参数
     */
    public PortalExampleService(
        CurrentPrincipalProvider principalProvider,
        AgentChatBIExampleMapper mapper,
        DataCatalogMapper catalogMapper,
        ReadOnlySqlValidator sqlValidator,
        JsonMapper jsonMapper,
        PlatformIdGenerator idGenerator,
        PortalChatBIModelGateway modelGateway,
        AgentChatBIExampleRevisionMapper revisionMapper,
        PortalExampleAuditService auditService
    ) {
        this.principalProvider = principalProvider;
        this.mapper = mapper;
        this.catalogMapper = catalogMapper;
        this.sqlValidator = sqlValidator;
        this.jsonMapper = jsonMapper;
        this.idGenerator = idGenerator;
        this.modelGateway = modelGateway;
        this.revisionMapper = revisionMapper;
        this.auditService = auditService;
    }

    /**
     * 处理{@code record}相关逻辑。
     *
     * @param principal 当前操作主体
     * @param assistantMessage 待处理内容
     * @param previousUserMessage 待处理内容
     * @param feedbackType 业务类型
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void record(
        CurrentPrincipal principal,
        ConversationMessageRow assistantMessage,
        ConversationMessageRow previousUserMessage,
        String feedbackType
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (principal == null || !principal.isHuman() || assistantMessage == null
            || !"assistant".equals(assistantMessage.getRole())) {
            throw new ServiceException("反馈案例缺少已验证的用户或助手消息", HttpStatus.CONFLICT);
        }
        String traceId = requiredServerText(assistantMessage.getTraceId(), 64, "消息 Trace");
        String answer = requiredServerText(assistantMessage.getContent(), 200_000, "助手回答");
        AgentDataQuery query = catalogMapper.selectLatestSucceededQueryByTrace(traceId, principal.id());

        String fallbackQuestion = previousUserMessage == null ? null : previousUserMessage.getContent();
        String userQuery = query == null || query.getUserQuery() == null || query.getUserQuery().isBlank()
            ? requiredServerText(fallbackQuestion, 200_000, "用户问题")
            : requiredServerText(query.getUserQuery(), 200_000, "数据查询问题");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "conversation_feedback");
        metadata.put("conversation_id", assistantMessage.getConversationId());
        metadata.put("assistant_message_id", assistantMessage.getId());
        metadata.put("assistant_sequence_no", assistantMessage.getSequenceNo());

        AgentChatBIExample candidate = new AgentChatBIExample();
        candidate.setId(idGenerator.nextId());
        candidate.setTraceId(traceId);
        candidate.setAgentId(assistantMessage.getAgentId() == null ? null : assistantMessage.getAgentId().toString());
        candidate.setUserQuery(userQuery);
        candidate.setAiAnswer(answer);
        candidate.setFeedbackType(enumValue(feedbackType, Set.of("up", "down"), "反馈类型"));
        candidate.setReviewStatus("pending");
        candidate.setEnhanceStatus("not_requested");
        candidate.setUseCount(0);
        candidate.setLocalSyncStatus("pending");
        candidate.setCreatedBy(principal.id());
        LocalDateTime now = LocalDateTime.now();
        candidate.setCreatedAt(now);
        candidate.setUpdatedAt(now);

        if (query == null) {
            candidate.setCategory("general");
            candidate.setSqlText("");
        } else {
            if (query.getDatasetId() == null || query.getDatasetId() <= 0
                || query.getSqlText() == null || query.getSqlText().isBlank()) {
                throw new ServiceException("成功数据查询事实缺少数据集或 SQL", HttpStatus.CONFLICT);
            }
            candidate.setCategory("data_query");
            candidate.setDatasetId(query.getDatasetId());
            candidate.setSqlText(requiredServerText(query.getSqlText(), 64 * 1024, "已执行 SQL"));
            metadata.put("data_query_id", query.getId());
            metadata.put("data_source_id", query.getDataSourceId());
            metadata.put("data_source_revision", query.getDataSourceRevision());
            metadata.put("dataset_revision", query.getDatasetRevision());
            metadata.put("sql_hash", query.getSqlHash());
            metadata.put("row_count", query.getRowCount());
            metadata.put("result_bytes", query.getResultBytes());
            metadata.put("result_truncated", query.getResultTruncated());
            metadata.put("finished_at", query.getFinishedAt() == null ? null : query.getFinishedAt().toString());
        }
        candidate.setSqlMetadataJson(json(metadata));
        if (mapper.upsertFeedbackCandidate(candidate) != 1) {
            throw new ServiceException("反馈案例 Trace 已由其他用户占用", HttpStatus.CONFLICT);
        }
        AgentChatBIExample saved = mapper.selectByTrace(traceId);
        String action = saved != null && !Objects.equals(saved.getId(), candidate.getId()) ? "updated" : "created";
        auditService.record(principal, action, saved == null ? candidate : saved, "conversation_feedback");
    }

    /**
     * 查询{@code list}列表。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    public PageResult list(ListRequest request) {
        CurrentPrincipal principal = human();
        ListRequest value = request == null ? new ListRequest(null, null, null, null, null, null, 1, 20) : request;
        int page = positive(value.page(), 1, "页码");
        int size = bounded(value.size(), 20, 1, MAX_PAGE_SIZE, "每页数量");
        String agentId = optionalText(value.agentId(), 128, "Agent标识");
        String status = optionalEnum(value.status(), REVIEW_STATUSES, "审核状态");
        String category = optionalEnum(value.category(), CATEGORIES, "案例分类");
        String search = optionalText(value.search(), 512, "搜索关键字");
        boolean admin = isPrivilegedViewer(principal);
        int offset = (page - 1) * size;
        long total = mapper.countVisible(
            principal.id(), admin, value.id(), agentId, value.datasetId(), status, category, search
        );
        List<AgentChatBIExample> rows = mapper.selectPage(
            principal.id(), admin, value.id(), agentId, value.datasetId(), status, category, search,
            size, offset
        );
        return new PageResult(
            total,
            rows.stream().map(this::view).toList(),
            page,
            size
        );
    }

    /**
     * 获取{@code get}。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    public Map<String, Object> get(Long id) {
        return view(requireVisible(id));
    }

    /**
     * 处理历史记录并返回对应结果。
     *
     * @param id 资源标识
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> history(Long id) {
        requireVisible(id);
        return revisionMapper.selectHistory(id, 100).stream().map(this::revisionView).toList();
    }

    /**
     * 更新{@code update}。
     *
     * @param id 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> update(Long id, UpdateRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = human();
        if (request == null) {
            throw badRequest("案例更新内容不能为空");
        }
        AgentChatBIExample current = requireVisible(id);
        AgentChatBIExample value = copy(current);
        boolean sqlChanged = false;
        if (request.userQuery() != null) {
            value.setUserQuery(requiredText(request.userQuery(), 200_000, "用户问题"));
        }
        if (request.refinedQuery() != null) {
            value.setRefinedQuery(optionalText(request.refinedQuery(), 200_000, "增强问题"));
        }
        if (request.contextSummary() != null) {
            value.setContextSummary(optionalText(request.contextSummary(), 200_000, "上下文摘要"));
        }
        if (request.sqlText() != null) {
            String sql = requiredText(request.sqlText(), 64 * 1024, "SQL");
            sqlChanged = !sql.equals(current.getSqlText());
            value.setSqlText(sql);
        }
        if (request.sqlMetadata() != null) {
            value.setSqlMetadataJson(json(request.sqlMetadata()));
        }
        if (request.category() != null) {
            value.setCategory(enumValue(request.category(), CATEGORIES, "案例分类"));
        }
        boolean contentChanged = !Objects.equals(current.getUserQuery(), value.getUserQuery())
            || !Objects.equals(current.getRefinedQuery(), value.getRefinedQuery())
            || !Objects.equals(current.getContextSummary(), value.getContextSummary())
            || !Objects.equals(current.getSqlText(), value.getSqlText())
            || !Objects.equals(current.getSqlMetadataJson(), value.getSqlMetadataJson())
            || !Objects.equals(current.getCategory(), value.getCategory());
        if (sqlChanged) {
            validateSql(value);
        }
        if (contentChanged) {
            // Edited content must be reviewed again before it can be indexed for runtime use.
            value.setReviewStatus("pending");
        }
        value.setUpdatedAt(LocalDateTime.now());
        if (mapper.updateContent(value, principal.id(), isAdmin(principal), current.getUpdatedAt()) != 1) {
            throw new ServiceException("案例已被其他操作修改，请刷新后重试", HttpStatus.CONFLICT);
        }
        AgentChatBIExample updated = requireVisible(id);
        if (contentChanged) {
            auditService.record(principal, "updated", updated, "content_edited_requires_review");
        }
        return view(updated);
    }

    /**
     * 处理审计并返回对应结果。
     *
     * @param id 资源标识
     * @param status 目标状态
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> audit(Long id, String status) {
        CurrentPrincipal principal = human();
        requireReviewer(principal);
        String normalized = enumValue(status, REVIEW_STATUSES, "审核状态");
        if ("pending".equals(normalized)) {
            throw badRequest("审核结果不能设置为 pending");
        }
        AgentChatBIExample current = requireForAdmin(id);
        if ("approved".equals(normalized) && "data_query".equals(current.getCategory())) {
            validateSql(current);
        }
        LocalDateTime now = LocalDateTime.now();
        if (mapper.updateReview(id, normalized, now, current.getReviewStatus()) != 1) {
            throw new ServiceException("案例不存在或审核状态已变化", HttpStatus.CONFLICT);
        }
        AgentChatBIExample updated = requireForAdmin(id);
        auditService.record(principal, "reviewed", updated, "review_status=" + normalized);
        return view(updated);
    }

    /**
     * 处理{@code enhance}并返回对应结果。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    public Map<String, Object> enhance(Long id) {
        CurrentPrincipal principal = human();
        AgentChatBIExample row = requireVisible(id);
        LocalDateTime startedAt = LocalDateTime.now();
        if (mapper.claimEnhancement(id, principal.id(), isAdmin(principal), startedAt) != 1) {
            throw new ServiceException("案例正在执行智能增强，请稍后重试", HttpStatus.CONFLICT);
        }
        try {
            PortalChatBIModelGateway.Completion completion = modelGateway.complete(
                ENHANCE_SYSTEM_PROMPT, enhancementPrompt(row)
            );
            Enhancement enhancement = parseEnhancement(row, completion);
            if (mapper.completeEnhancement(
                id, enhancement.refinedQuery(), enhancement.contextSummary(),
                enhancement.sqlMetadataJson(), LocalDateTime.now()
            ) != 1) {
                throw new ServiceException("案例智能增强状态已变化", HttpStatus.CONFLICT);
            }
            AgentChatBIExample updated = requireVisible(id);
            auditService.record(principal, "enhanced", updated, "model_id=" + completion.modelId());
            return view(updated);
        } catch (RuntimeException exception) {
            mapper.failEnhancement(id, enhancementError(exception), LocalDateTime.now());
            AgentChatBIExample failed = requireVisible(id);
            auditService.recordFailure(principal, "enhanced", failed, enhancementError(exception));
            throw exception;
        }
    }

    /**
     * 处理{@code sync}并返回对应结果。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> sync(Long id) {
        CurrentPrincipal principal = human();
        requireReviewer(principal);
        AgentChatBIExample row = requireForAdmin(id);
        if (!SYNCABLE_STATUSES.contains(row.getReviewStatus())) {
            throw new ServiceException("只有审核通过或已废弃的案例允许同步", HttpStatus.BAD_REQUEST);
        }
        syncOne(principal, row);
        return view(requireForAdmin(id));
    }

    /**
     * 处理{@code syncAll}并返回对应结果。
     *
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> syncAll() {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = human();
        requireReviewer(principal);
        long approved = mapper.countVisible(
            principal.id(), true, null, null, null, "approved", null, null
        );
        long deprecated = mapper.countVisible(
            principal.id(), true, null, null, null, "deprecated", null, null
        );
        int synced = 0;
        int failed = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        for (String status : SYNCABLE_STATUSES) {
            int offset = 0;
            while (true) {
                List<AgentChatBIExample> rows = mapper.selectPage(
                    principal.id(), true, null, null, null, status, null, null,
                    SYNC_PAGE_SIZE, offset
                );
                if (rows.isEmpty()) {
                    break;
                }
                for (AgentChatBIExample row : rows) {
                    try {
                        syncOne(principal, row);
                        synced++;
                    } catch (ServiceException exception) {
                        failed++;
                        errors.add(Map.of(
                            "id", row.getId(),
                            "message", exception.getMessage() == null ? "同步失败" : exception.getMessage()
                        ));
                        mapper.updateLocalSync(
                            row.getId(), "failed", exception.getMessage(), null, LocalDateTime.now()
                        );
                        row.setLocalSyncStatus("failed");
                        row.setLocalSyncError(exception.getMessage());
                        auditService.recordFailure(principal, "synced", row, exception.getMessage());
                    }
                }
                offset += rows.size();
                if (rows.size() < SYNC_PAGE_SIZE) {
                    break;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", failed == 0 ? "success" : "partial_failure");
        result.put("total", approved + deprecated);
        result.put("synced", synced);
        result.put("failed", failed);
        result.put("errors", errors);
        result.put("index", "relational");
        return result;
    }

    /**
     * 删除{@code delete}。
     *
     * @param id 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        CurrentPrincipal principal = human();
        AgentChatBIExample current = requireVisible(id);
        if (mapper.delete(id, principal.id(), isAdmin(principal), LocalDateTime.now()) != 1) {
            throw new ServiceException("案例不存在或没有删除权限", HttpStatus.FORBIDDEN);
        }
        auditService.record(principal, "deleted", current, "soft_delete");
    }

    /**
     * 处理{@code syncOne}相关逻辑。
     *
     * @param principal 当前操作主体
     * @param row {@code row}参数
     */
    private void syncOne(CurrentPrincipal principal, AgentChatBIExample row) {
        if ("approved".equals(row.getReviewStatus())
            && !"down".equals(row.getFeedbackType())
            && "data_query".equals(row.getCategory())) {
            validateSql(row);
        }
        LocalDateTime now = LocalDateTime.now();
        if (mapper.updateLocalSync(row.getId(), "synced", null, now, now) != 1) {
            throw new ServiceException("案例本地索引状态写入失败", HttpStatus.CONFLICT);
        }
        // Keep the returned view consistent with the durable update even when the
        // persistence adapter does not mutate the in-memory row instance.
        row.setLocalSyncStatus("synced");
        row.setLocalSyncError(null);
        row.setLocalSyncedAt(now);
        auditService.record(principal, "synced", row, "local_relational_index");
    }

    /**
     * 校验{@code Sql}，并在条件不满足时终止处理。
     *
     * @param row {@code row}参数
     */
    private void validateSql(AgentChatBIExample row) {
        if (row.getDatasetId() == null || row.getDatasetId() <= 0) {
            throw new ServiceException("案例缺少数据集，无法按平台数据权限校验 SQL", HttpStatus.CONFLICT);
        }
        List<AgentDataTable> tables = catalogMapper.selectTables(row.getDatasetId());
        List<AgentDataColumn> columns = catalogMapper.selectColumns(row.getDatasetId());
        sqlValidator.validate(row.getSqlText(), tables, columns);
    }

    /**
     * 校验{@code Visible}，并在条件不满足时终止处理。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    private AgentChatBIExample requireVisible(Long id) {
        if (id == null || id <= 0) {
            throw badRequest("案例标识无效");
        }
        CurrentPrincipal principal = human();
        List<AgentChatBIExample> rows = mapper.selectPage(
            principal.id(), isPrivilegedViewer(principal), id, null, null, null, null, null, 1, 0
        );
        if (rows.isEmpty()) {
            throw new ServiceException("案例不存在或没有访问权限", HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    /**
     * 校验{@code ForAdmin}，并在条件不满足时终止处理。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    private AgentChatBIExample requireForAdmin(Long id) {
        if (id == null || id <= 0) {
            throw badRequest("案例标识无效");
        }
        CurrentPrincipal principal = human();
        List<AgentChatBIExample> rows = mapper.selectPage(
            principal.id(), true, id, null, null, null, null, null, 1, 0
        );
        if (rows.isEmpty()) {
            throw new ServiceException("案例不存在", HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    /**
     * 处理{@code human}并返回对应结果。
     *
     * @return 处理结果
     */
    private CurrentPrincipal human() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (!principal.isHuman()) {
            throw new ServiceException("机器主体不能操作门户案例", HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    /**
     * 校验{@code Reviewer}，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     */
    private void requireReviewer(CurrentPrincipal principal) {
        if (!principal.hasRole(PlatformRole.PLATFORM_ADMIN)
            && !principal.hasRole(PlatformRole.APPROVAL_USER)) {
            throw new ServiceException("只有平台管理员或审核人员可以执行案例审核和同步", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 判断{@code Admin}是否满足要求。
     *
     * @param principal 当前操作主体
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isAdmin(CurrentPrincipal principal) {
        return principal.hasRole(PlatformRole.PLATFORM_ADMIN);
    }

    /**
     * 判断{@code PrivilegedViewer}是否满足要求。
     *
     * @param principal 当前操作主体
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isPrivilegedViewer(CurrentPrincipal principal) {
        return isAdmin(principal) || principal.hasRole(PlatformRole.APPROVAL_USER);
    }

    /**
     * 处理{@code copy}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private AgentChatBIExample copy(AgentChatBIExample source) {
        AgentChatBIExample value = new AgentChatBIExample();
        value.setId(source.getId());
        value.setTraceId(source.getTraceId());
        value.setAgentId(source.getAgentId());
        value.setDatasetId(source.getDatasetId());
        value.setUserQuery(source.getUserQuery());
        value.setRefinedQuery(source.getRefinedQuery());
        value.setContextSummary(source.getContextSummary());
        value.setSqlText(source.getSqlText());
        value.setSqlMetadataJson(source.getSqlMetadataJson());
        value.setCategory(source.getCategory());
        value.setReviewStatus(source.getReviewStatus());
        value.setCreatedBy(source.getCreatedBy());
        return value;
    }

    /**
     * 处理{@code view}并返回对应结果。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    private Map<String, Object> view(AgentChatBIExample row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.getId());
        result.put("trace_id", row.getTraceId());
        result.put("agent_id", row.getAgentId());
        result.put("dataset_id", row.getDatasetId());
        result.put("user_query", row.getUserQuery());
        result.put("refined_query", row.getRefinedQuery());
        result.put("context_summary", row.getContextSummary());
        result.put("sql_text", row.getSqlText());
        result.put("sql_metadata", parseJson(row.getSqlMetadataJson()));
        result.put("category", row.getCategory());
        result.put("enhance_status", row.getEnhanceStatus());
        result.put("ai_answer", row.getAiAnswer());
        result.put("feedback_type", row.getFeedbackType());
        result.put("review_status", row.getReviewStatus());
        result.put("error_message", row.getErrorMessage());
        result.put("use_count", row.getUseCount());
        result.put("local_sync_status", row.getLocalSyncStatus());
        result.put("local_sync_error", row.getLocalSyncError());
        result.put("local_synced_at", row.getLocalSyncedAt());
        result.put("created_by", row.getCreatedBy());
        result.put("created_at", row.getCreatedAt());
        result.put("updated_at", row.getUpdatedAt());
        return result;
    }

    /**
     * 处理{@code revisionView}并返回对应结果。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    private Map<String, Object> revisionView(AgentChatBIExampleRevision row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.getId());
        result.put("revision_no", row.getRevisionNo());
        result.put("example_id", row.getExampleId());
        result.put("action", row.getAction());
        result.put("review_status", row.getReviewStatus());
        result.put("user_query", row.getUserQuery());
        result.put("refined_query", row.getRefinedQuery());
        result.put("context_summary", row.getContextSummary());
        result.put("sql_text", row.getSqlText());
        result.put("sql_metadata", parseJson(row.getSqlMetadataJson()));
        result.put("category", row.getCategory());
        result.put("enhance_status", row.getEnhanceStatus());
        result.put("local_sync_status", row.getLocalSyncStatus());
        result.put("actor_type", row.getActorType());
        result.put("actor_id", row.getActorId());
        result.put("reason", row.getReason());
        result.put("content_hash", row.getContentHash());
        result.put("created_at", row.getCreatedAt());
        return result;
    }

    /**
     * 处理{@code parseJson}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 处理结果
     */
    private Object parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(raw, Object.class);
        } catch (RuntimeException exception) {
            return Map.of("raw", raw);
        }
    }

    /**
     * 处理enhancement提示词并返回对应结果。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    private String enhancementPrompt(AgentChatBIExample row) {
        return "【当前问题】\n" + clipped(row.getUserQuery(), 12_000)
            + "\n\n【助手回答】\n" + clipped(row.getAiAnswer(), 16_000)
            + "\n\n【已执行 SQL】\n" + clipped(row.getSqlText(), 16_000)
            + "\n\n【既有结构化元数据】\n" + clipped(row.getSqlMetadataJson(), 8_000);
    }

    /**
     * 处理{@code parseEnhancement}并返回对应结果。
     *
     * @param row {@code row}参数
     * @param completion {@code completion}参数
     * @return 处理结果
     */
    private Enhancement parseEnhancement(
        AgentChatBIExample row,
        PortalChatBIModelGateway.Completion completion
    ) {
        String content = completion.content() == null ? "" : completion.content().strip();
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start || end - start > 64 * 1024) {
            throw new ServiceException("案例智能增强模型未返回有效 JSON", 502);
        }
        Map<String, Object> value;
        try {
            value = jsonMapper.readValue(content.substring(start, end + 1), MAP_TYPE);
        } catch (RuntimeException exception) {
            throw new ServiceException("案例智能增强模型返回的 JSON 无法解析", 502);
        }
        String refinedQuery = generatedText(value.get("refined_query"), 12_000, "完整问题");
        String contextSummary = generatedText(value.get("context_summary"), 2_000, "上下文摘要");
        Map<String, Object> metadata = enhancementMetadata(row, value.get("sql_metadata"));
        metadata.put("enhancement_model_id", completion.modelId());
        metadata.put("enhanced_at", LocalDateTime.now().toString());
        return new Enhancement(refinedQuery, contextSummary, json(metadata));
    }

    /**
     * 处理enhancement元数据并返回对应结果。
     *
     * @param row {@code row}参数
     * @param raw {@code raw}参数
     * @return 处理结果
     */
    private Map<String, Object> enhancementMetadata(AgentChatBIExample row, Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object existing = parseJson(row.getSqlMetadataJson());
        if (existing instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
        }
        if (!(raw instanceof Map<?, ?> metadata)) {
            throw new ServiceException("案例智能增强缺少 SQL 元数据", 502);
        }
        result.put("tables", generatedTextList(metadata.get("tables"), "涉及表"));
        result.put("query_type", generatedText(metadata.get("query_type"), 64, "查询类型"));
        result.put("dimensions", generatedTextList(metadata.get("dimensions"), "核心维度"));
        return result;
    }

    /**
     * 处理{@code generatedTextList}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @param label {@code label}参数
     * @return 符合条件的数据集合
     */
    private List<String> generatedTextList(Object raw, String label) {
        if (!(raw instanceof List<?> values) || values.size() > 100) {
            throw new ServiceException("案例智能增强返回的" + label + "无效", 502);
        }
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            result.add(generatedText(value, 255, label));
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code generatedText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String generatedText(Object value, int max, String label) {
        if (!(value instanceof String text)) {
            throw new ServiceException("案例智能增强返回的" + label + "无效", 502);
        }
        String normalized = text.strip();
        if (normalized.isBlank() || normalized.length() > max || normalized.indexOf('\0') >= 0) {
            throw new ServiceException("案例智能增强返回的" + label + "无效", 502);
        }
        return normalized;
    }

    /**
     * 处理{@code clipped}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @return 处理结果
     */
    private String clipped(String value, int max) {
        if (value == null || value.isBlank()) {
            return "（无）";
        }
        String normalized = value.replace('\0', ' ').strip();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    /**
     * 处理{@code enhancementError}并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    private String enhancementError(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "案例智能增强失败";
        }
        String normalized = message.replaceAll("[\\r\\n]+", " ").strip();
        return normalized.length() <= 2_000 ? normalized : normalized.substring(0, 2_000);
    }

    /**
     * 处理{@code json}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String json(Map<String, Object> value) {
        try {
            String result = jsonMapper.writeValueAsString(value);
            if (result.length() > 64 * 1024) {
                throw badRequest("SQL元数据超过 64KB 限制");
            }
            return result;
        } catch (ServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw badRequest("SQL元数据不是有效 JSON");
        }
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String requiredText(String value, int max, String label) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > max || normalized.indexOf('\0') >= 0) {
            throw badRequest(label + "为空或超过长度限制");
        }
        return normalized;
    }

    /**
     * 校验{@code dServerText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String requiredServerText(String value, int max, String label) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > max || normalized.indexOf('\0') >= 0) {
            throw new ServiceException(label + "缺失或超过存储限制", HttpStatus.CONFLICT);
        }
        return normalized;
    }

    /**
     * 处理{@code optionalText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String optionalText(String value, int max, String label) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.length() > max || normalized.indexOf('\0') >= 0) {
            throw badRequest(label + "超过长度限制");
        }
        return normalized;
    }

    /**
     * 处理{@code enumValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param allowed {@code allowed}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String enumValue(String value, Set<String> allowed, String label) {
        String normalized = requiredText(value, 32, label).toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw badRequest(label + "无效");
        }
        return normalized;
    }

    /**
     * 处理{@code optionalEnum}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param allowed {@code allowed}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String optionalEnum(String value, Set<String> allowed, String label) {
        return value == null || value.isBlank() ? null : enumValue(value, allowed, label);
    }

    /**
     * 处理{@code positive}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param fallback {@code fallback}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private int positive(Integer value, int fallback, String label) {
        int normalized = value == null ? fallback : value;
        if (normalized < 1) {
            throw badRequest(label + "必须大于 0");
        }
        return normalized;
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param fallback {@code fallback}参数
     * @param min {@code min}参数
     * @param max {@code max}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private int bounded(Integer value, int fallback, int min, int max, String label) {
        int normalized = value == null ? fallback : value;
        if (normalized < min || normalized > max) {
            throw badRequest(label + "必须在 " + min + " 到 " + max + " 之间");
        }
        return normalized;
    }

    /**
     * 处理{@code badRequest}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 封装{@code List}相关的不可变数据。
     */
    public record ListRequest(
        Long id,
        String agentId,
        Long datasetId,
        String status,
        String category,
        String search,
        Integer page,
        Integer size
    ) {
    }

    /**
     * 封装{@code Update}相关的不可变数据。
     */
    public record UpdateRequest(
        String userQuery,
        String refinedQuery,
        String contextSummary,
        String sqlText,
        Map<String, Object> sqlMetadata,
        String category
    ) {
    }

    /**
     * 封装{@code Page}相关的不可变数据。
     */
    public record PageResult(long total, List<Map<String, Object>> items, int page, int size) {
    }

    /**
     * 封装{@code Enhancement}相关的不可变数据。
     */
    private record Enhancement(
        String refinedQuery,
        String contextSummary,
        String sqlMetadataJson
    ) {
    }
}
