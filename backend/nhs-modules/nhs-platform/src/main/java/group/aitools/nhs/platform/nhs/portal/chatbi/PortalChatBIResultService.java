package group.aitools.nhs.platform.nhs.portal.chatbi;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.service.ConversationApplicationService;
import group.aitools.nhs.platform.data.domain.AgentDataQuery;
import group.aitools.nhs.platform.data.persistence.row.DataQueryStoredResultRow;
import group.aitools.nhs.platform.data.service.DataQueryExecutionService;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 负责门户对话BI结果相关的业务编排与领域规则处理。
 * Owns durable ChatBI result lineage, evidence receipts, presentation state and drill-down references. */
@Service
public class PortalChatBIResultService {

    private static final int MAX_STACK_DEPTH = 10;
    private static final Set<String> CURRENT_REFERENCES = Set.of(
        "当前结果", "这个结果", "该结果", "刚才结果", "最新结果", "current", "latest"
    );
    private static final Set<String> PREVIOUS_REFERENCES = Set.of(
        "上一个结果", "前一个结果", "上一张表", "上一步", "previous"
    );

    private final CurrentPrincipalProvider principalProvider;
    private final ConversationApplicationService conversationService;
    private final DataQueryExecutionService queryExecutionService;
    private final PortalChatBIQueryMapper queryMapper;
    private final PortalChatBIResultMapper resultMapper;
    private final PortalChatBIFederatedMapper federatedMapper;
    private final PortalChatBIPresentationService presentationService;
    private final AgentAuditEventMapper auditMapper;
    private final PlatformIdGenerator idGenerator;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code PortalChatBIResultService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param conversationService 会话Service参数
     * @param queryExecutionService 查询执行Service参数
     * @param queryMapper 查询Mapper参数
     * @param resultMapper 结果Mapper参数
     * @param federatedMapper {@code federatedMapper}参数
     * @param presentationService {@code presentationService}参数
     * @param auditMapper 审计Mapper参数
     * @param idGenerator {@code idGenerator}参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public PortalChatBIResultService(
        CurrentPrincipalProvider principalProvider,
        ConversationApplicationService conversationService,
        DataQueryExecutionService queryExecutionService,
        PortalChatBIQueryMapper queryMapper,
        PortalChatBIResultMapper resultMapper,
        PortalChatBIFederatedMapper federatedMapper,
        PortalChatBIPresentationService presentationService,
        AgentAuditEventMapper auditMapper,
        PlatformIdGenerator idGenerator,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.conversationService = conversationService;
        this.queryExecutionService = queryExecutionService;
        this.queryMapper = queryMapper;
        this.resultMapper = resultMapper;
        this.federatedMapper = federatedMapper;
        this.presentationService = presentationService;
        this.auditMapper = auditMapper;
        this.idGenerator = idGenerator;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 获取{@code Parent}。
     *
     * @param principal 当前操作主体
     * @param conversationId 资源标识
     * @param datasetId 资源标识
     * @param parentResultId 资源标识
     * @param resultReference 结果Reference参数
     * @return 处理结果
     */
    public ParentResult resolveParent(
        CurrentPrincipal principal,
        Long conversationId,
        Long datasetId,
        Long parentResultId,
        String resultReference
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (parentResultId != null && hasText(resultReference)) {
            throw badRequest("父结果 ID 和结果引用不能同时提供");
        }
        if (conversationId == null) {
            if (parentResultId != null || hasText(resultReference)) {
                throw badRequest("引用历史结果时必须提供所属 ChatBI 会话");
            }
            return null;
        }
        conversationService.get(conversationId);
        List<AgentDataQuery> stack = resultMapper.selectOwnedStackQueries(
            conversationId, principal.id(), MAX_STACK_DEPTH
        ).stream().filter(query -> Objects.equals(query.getDatasetId(), datasetId)).toList();
        if (stack.isEmpty()) {
            if (parentResultId != null || hasText(resultReference)) {
                throw new ServiceException("引用的 ChatBI 结果不存在或当前无权访问", HttpStatus.NOT_FOUND);
            }
            return null;
        }
        AgentDataQuery selected;
        if (parentResultId != null) {
            selected = stack.stream().filter(query -> query.getId().equals(parentResultId)).findFirst().orElse(null);
        } else {
            selected = resolveReference(stack, resultReference);
        }
        if (selected == null) {
            throw new ServiceException("引用的 ChatBI 结果不存在或不属于当前会话", HttpStatus.NOT_FOUND);
        }
        requireResultDatasets(selected, principal.id());
        AgentChatBIResultContext context = resultMapper.selectOwnedContext(selected.getId(), principal.id());
        AgentChatBIEvidence evidence = resultMapper.selectOwnedEvidence(selected.getId(), principal.id());
        if (evidence == null) {
            throw new ServiceException("引用结果缺少有效数据证据，不能用于连续分析", HttpStatus.CONFLICT);
        }
        return new ParentResult(
            selected.getId(), selected.getConversationId(), selected.getDatasetId(),
            selected.getUserQuery(), context == null ? Map.of() : jsonObject(context.getAnalysisContextJson()),
            evidence.getSourceRef(), evidence.getObservedAt()
        );
    }

    /**
     * 处理inherited提示词并返回对应结果。
     *
     * @param parent {@code parent}参数
     * @param question 追问参数
     * @return 处理结果
     */
    public String inheritedPrompt(ParentResult parent, String question) {
        if (parent == null) {
            return "";
        }
        Map<String, Object> inherited = new LinkedHashMap<>();
        inherited.put("parent_result_id", String.valueOf(parent.queryId()));
        inherited.put("previous_question", parent.question());
        inherited.put("analysis_context", parent.analysisContext());
        inherited.put("source_ref", parent.sourceRef());
        inherited.put("observed_at", parent.observedAt());
        return """

            【连续分析：继承父结果语义】
            本轮引用一个当前用户有权访问的既有 ChatBI 结果。默认保留用户未明确修改的业务对象、
            指标、筛选条件和时间范围，只切换本轮指定的维度、粒度或分析方法。
            父结果语义 JSON：%s
            本轮变化请求：%s
            必须基于当前授权 Schema 重新生成 SQL，禁止复制或改写父结果的 SQL 文本；发生条件变化时，
            在 analysis_context 中返回最终采用的语义条件。
            """.formatted(jsonMapper.writeValueAsString(inherited), bounded(question, 4000));
    }

    /**
     * 处理recordExecuted结果相关逻辑。
     *
     * @param principal 当前操作主体
     * @param queryId 资源标识
     * @param parent {@code parent}参数
     * @param analysisContext 待处理内容
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordExecutedResult(
        CurrentPrincipal principal,
        Long queryId,
        ParentResult parent,
        Map<String, Object> analysisContext
    ) {
        OwnedResult owned = ownedResult(queryId, principal, true);
        if (parent != null && (!Objects.equals(parent.conversationId(), owned.query().getConversationId())
            || !Objects.equals(parent.datasetId(), owned.query().getDatasetId()))) {
            throw new ServiceException("父结果与当前查询不在同一会话和数据集", HttpStatus.CONFLICT);
        }
        persistResult(owned, parent == null ? null : parent.queryId(), analysisContext, principal);
    }

    /**
     * 处理{@code decorate}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param queryId 资源标识
     * @param target {@code target}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> decorate(
        CurrentPrincipal principal,
        Long queryId,
        Map<String, Object> target
    ) {
        OwnedResult owned = ownedResult(queryId, principal.id(), true);
        AgentChatBIResultContext context = resultMapper.selectOwnedContext(queryId, principal.id());
        AgentChatBIEvidence evidence = resultMapper.selectOwnedEvidence(queryId, principal.id());
        if (context == null || evidence == null) {
            persistResult(owned, null, Map.of(), principal);
            context = resultMapper.selectOwnedContext(queryId, principal.id());
            evidence = resultMapper.selectOwnedEvidence(queryId, principal.id());
        }
        List<String> columns = columns(owned.result().getColumnsJson());
        List<List<Object>> rows = rows(owned.result().getRowsJson());
        target.put("parent_result_id", context == null ? null : context.getParentQueryId());
        target.put("analysis_context", context == null ? Map.of() : jsonObject(context.getAnalysisContextJson()));
        target.put("evidence", evidenceView(evidence));
        target.put("presentation", presentationView(context, columns, rows));
        return target;
    }

    /**
     * 处理{@code stack}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> stack(Long conversationId, int limit) {
        CurrentPrincipal principal = requireHuman();
        conversationService.get(conversationId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentDataQuery query : resultMapper.selectOwnedStackQueries(
            conversationId, principal.id(), Math.max(1, Math.min(limit, MAX_STACK_DEPTH))
        )) {
            try {
                requireResultDatasets(query, principal.id());
            } catch (ServiceException exception) {
                if (Set.of(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND, HttpStatus.CONFLICT).contains(exception.getCode())) {
                    continue;
                }
                throw exception;
            }
            AgentChatBIResultContext context = resultMapper.selectOwnedContext(query.getId(), principal.id());
            AgentChatBIEvidence evidence = resultMapper.selectOwnedEvidence(query.getId(), principal.id());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("query_id", query.getId());
            item.put("result_id", String.valueOf(query.getId()));
            item.put("parent_result_id", context == null ? null : context.getParentQueryId());
            item.put("conversation_id", query.getConversationId());
            item.put("dataset_id", query.getDatasetId());
            item.put("question", query.getUserQuery());
            item.put("row_count", query.getRowCount() == null ? 0 : query.getRowCount());
            item.put("created_at", query.getCreatedAt());
            item.put("evidence_status", evidence == null ? "missing" : evidence.getResultStatus());
            item.put("revision", context == null ? 1 : context.getRevisionNo());
            result.add(item);
        }
        return List.copyOf(result);
    }

    /**
     * 更新{@code Presentation}。
     *
     * @param queryId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updatePresentation(Long queryId, PresentationUpdate request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = requireHuman();
        OwnedResult owned = ownedResult(queryId, principal.id(), true);
        AgentChatBIResultContext context = resultMapper.selectOwnedContext(queryId, principal.id());
        if (context == null || resultMapper.selectOwnedEvidence(queryId, principal.id()) == null) {
            persistResult(owned, null, Map.of(), principal);
            context = resultMapper.selectOwnedContext(queryId, principal.id());
        }
        if (context == null) {
            throw new ServiceException("ChatBI 结果上下文创建失败", HttpStatus.ERROR);
        }
        if (request == null || request.expectedRevision() == null || request.expectedRevision() < 1) {
            throw badRequest("展示配置版本号无效");
        }
        if (request.chart() == null && request.pivot() == null) {
            throw badRequest("至少需要更新图表或透视表配置");
        }
        List<String> columns = columns(owned.result().getColumnsJson());
        List<List<Object>> rows = rows(owned.result().getRowsJson());
        PortalChatBIPresentationService.ChartConfig chart = request.chart() == null
            ? presentationService.parseChart(context.getChartConfigJson(), columns, rows)
            : request.chart();
        PortalChatBIPresentationService.PivotConfig pivot = request.pivot() == null
            ? presentationService.parsePivot(context.getPivotConfigJson(), columns, rows)
            : request.pivot();
        PortalChatBIPresentationService.Presentation presentation = presentationService.materialize(
            columns, rows, chart, pivot
        );
        LocalDateTime now = LocalDateTime.now();
        if (resultMapper.updatePresentation(
            queryId, principal.id(), request.expectedRevision(),
            presentationService.chartJson(presentation.chart()),
            presentationService.pivotJson(presentation.pivot()), now
        ) != 1) {
            throw new ServiceException("展示配置已被其他操作更新，请刷新结果后重试", HttpStatus.CONFLICT);
        }
        audit(
            principal, "update_presentation", "chatbi_result", queryId,
            "revision=" + (request.expectedRevision() + 1) + ", chart=" + presentation.chart().type()
        );
        Map<String, Object> value = new LinkedHashMap<>(presentationService.view(presentation));
        value.put("revision", request.expectedRevision() + 1);
        return value;
    }

    /**
     * 处理{@code prepareDrilldown}并返回对应结果。
     *
     * @param queryId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public DrilldownTarget prepareDrilldown(Long queryId, DrilldownRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = requireHuman();
        OwnedResult owned = ownedResult(queryId, principal.id(), true);
        if (request == null) {
            throw badRequest("下钻条件不能为空");
        }
        String dimension = required(request.dimension(), 255, "下钻维度");
        List<String> columns = columns(owned.result().getColumnsJson());
        int index = columns.indexOf(dimension);
        if (index < 0) {
            throw badRequest("下钻维度不在当前结果字段中");
        }
        JsonNode valueNode = jsonMapper.readTree(jsonMapper.writeValueAsString(request.value()));
        if (valueNode == null || (!valueNode.isValueNode() && !valueNode.isNull())) {
            throw badRequest("下钻值只支持文本、数字、布尔值或空值");
        }
        String canonical = jsonMapper.writeValueAsString(request.value());
        if (canonical.length() > 2000) {
            throw badRequest("下钻值超过长度限制");
        }
        boolean present = rows(owned.result().getRowsJson()).stream().anyMatch(row ->
            index < row.size() && canonical.equals(jsonMapper.writeValueAsString(row.get(index)))
        );
        if (!present) {
            throw new ServiceException("下钻值不属于当前持久化结果", HttpStatus.CONFLICT);
        }
        String userInstruction = nullable(request.question());
        if (userInstruction != null && (userInstruction.length() > 2000 || userInstruction.indexOf('\0') >= 0)) {
            throw badRequest("下钻问题超过长度限制");
        }
        String generated = "在父结果 result:" + queryId + " 的分析条件基础上，聚焦字段“"
            + dimension + "”等于 " + displayValue(request.value()) + " 继续下钻分析。"
            + (userInstruction == null ? "请解释该分组的构成、差异和可行动项。" : userInstruction);
        audit(
            principal, "drilldown", "chatbi_result", queryId,
            "dimension=" + bounded(dimension, 120) + ", conversation=" + owned.query().getConversationId()
        );
        return new DrilldownTarget(
            owned.query().getDatasetId(), owned.query().getConversationId(), queryId,
            bounded(generated, 4000)
        );
    }

    /**
     * 处理persist结果相关逻辑。
     *
     * @param owned {@code owned}参数
     * @param parentQueryId 资源标识
     * @param analysisContext 待处理内容
     * @param principal 当前操作主体
     */
    private void persistResult(
        OwnedResult owned,
        Long parentQueryId,
        Map<String, Object> analysisContext,
        CurrentPrincipal principal
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        AgentDataQuery query = owned.query();
        DataQueryStoredResultRow stored = owned.result();
        List<String> columns = columns(stored.getColumnsJson());
        List<List<Object>> rows = rows(stored.getRowsJson());
        PortalChatBIPresentationService.Presentation defaults = presentationService.defaults(columns, rows);
        LocalDateTime now = query.getFinishedAt() == null ? LocalDateTime.now() : query.getFinishedAt();
        AgentChatBIResultContext context = new AgentChatBIResultContext();
        context.setQueryId(query.getId());
        context.setOwnerId(principal.id());
        context.setConversationId(query.getConversationId());
        context.setParentQueryId(parentQueryId);
        context.setAnalysisContextJson(boundedJson(analysisContext == null ? Map.of() : analysisContext, 32_000));
        context.setChartConfigJson(presentationService.chartJson(defaults.chart()));
        context.setPivotConfigJson(presentationService.pivotJson(defaults.pivot()));
        context.setRevisionNo(1);
        context.setCreatedAt(now);
        int contextInserted = resultMapper.insertContext(context);

        AgentChatBIEvidence evidence = new AgentChatBIEvidence();
        evidence.setId(idGenerator.nextId());
        evidence.setQueryId(query.getId());
        evidence.setOwnerId(principal.id());
        evidence.setConversationId(query.getConversationId());
        evidence.setTraceId(query.getTraceId());
        evidence.setDatasetId(query.getDatasetId());
        evidence.setEvidenceType("internal_data");
        evidence.setProducer("chatbi_query");
        evidence.setPayloadDigest(stored.getContentHash());
        evidence.setResultHash(stored.getContentHash());
        evidence.setSourceRef("dataset://" + query.getDatasetId() + "/query/" + query.getId());
        evidence.setResultStatus(stored.getRowCount() != null && stored.getRowCount() > 0
            ? "success_non_empty" : "success_empty");
        evidence.setFreshness("dynamic");
        evidence.setObservedAt(now);
        evidence.setPermissionSnapshotJson(validJsonObject(query.getPermissionSummaryJson()));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rowCount", query.getRowCount() == null ? 0 : query.getRowCount());
        detail.put("resultBytes", query.getResultBytes() == null ? 0 : query.getResultBytes());
        detail.put("truncated", Boolean.TRUE.equals(query.getResultTruncated()));
        detail.put("sqlHash", query.getSqlHash());
        evidence.setDetailJson(jsonMapper.writeValueAsString(detail));
        evidence.setCreatedAt(now);
        int evidenceInserted = resultMapper.insertEvidence(evidence);

        if (contextInserted == 1) {
            audit(
                principal, "create", "chatbi_result", query.getId(),
                "conversation=" + query.getConversationId() + ", parent=" + parentQueryId
            );
        }
        if (evidenceInserted == 1) {
            audit(
                principal, "sign_evidence", "chatbi_evidence", evidence.getId(),
                "query=" + query.getId() + ", status=" + evidence.getResultStatus()
            );
        }
    }

    /**
     * 处理owned结果并返回对应结果。
     *
     * @param queryId 资源标识
     * @param userId 资源标识
     * @param requireSucceeded {@code requireSucceeded}参数
     * @return 处理结果
     */
    private OwnedResult ownedResult(Long queryId, Long userId, boolean requireSucceeded) {
        AgentDataQuery query = queryMapper.selectOwnedQuery(queryId, userId);
        if (query == null || (requireSucceeded && !"succeeded".equals(query.getStatus()))) {
            throw new ServiceException("ChatBI 查询结果不存在", HttpStatus.NOT_FOUND);
        }
        requireResultDatasets(query, userId);
        DataQueryStoredResultRow stored = queryMapper.selectOwnedResult(queryId, userId);
        if (stored == null) {
            throw new ServiceException("ChatBI 查询结果快照不存在", HttpStatus.NOT_FOUND);
        }
        return new OwnedResult(query, stored);
    }

    /**
     * 处理owned结果并返回对应结果。
     *
     * @param queryId 资源标识
     * @param principal 当前操作主体
     * @param requireSucceeded {@code requireSucceeded}参数
     * @return 处理结果
     */
    private OwnedResult ownedResult(
        Long queryId,
        CurrentPrincipal principal,
        boolean requireSucceeded
    ) {
        AgentDataQuery query = queryMapper.selectOwnedQuery(queryId, principal.id());
        if (query == null || (requireSucceeded && !"succeeded".equals(query.getStatus()))) {
            throw new ServiceException("ChatBI 查询结果不存在", HttpStatus.NOT_FOUND);
        }
        requireResultDatasets(query, principal);
        DataQueryStoredResultRow stored = queryMapper.selectOwnedResult(queryId, principal.id());
        if (stored == null) {
            throw new ServiceException("ChatBI 查询结果快照不存在", HttpStatus.NOT_FOUND);
        }
        return new OwnedResult(query, stored);
    }

    /**
     * 校验结果Datasets，并在条件不满足时终止处理。
     *
     * @param query 查询参数
     * @param ownerId 资源标识
     */
    private void requireResultDatasets(AgentDataQuery query, Long ownerId) {
        queryExecutionService.requireInteractiveQueryAccess(query.getDatasetId());
        if (federatedMapper == null) return;
        for (Long datasetId : federatedMapper.selectOwnedDatasetIdsByResult(query.getId(), ownerId)) {
            if (!Objects.equals(datasetId, query.getDatasetId())) {
                queryExecutionService.requireInteractiveQueryAccess(datasetId);
            }
        }
    }

    /**
     * 校验结果Datasets，并在条件不满足时终止处理。
     *
     * @param query 查询参数
     * @param principal 当前操作主体
     */
    private void requireResultDatasets(AgentDataQuery query, CurrentPrincipal principal) {
        queryExecutionService.requireQueryAccess(principal, query.getDatasetId());
        if (federatedMapper == null) return;
        for (Long datasetId : federatedMapper.selectOwnedDatasetIdsByResult(query.getId(), principal.id())) {
            if (!Objects.equals(datasetId, query.getDatasetId())) {
                queryExecutionService.requireQueryAccess(principal, datasetId);
            }
        }
    }

    /**
     * 获取{@code Reference}。
     *
     * @param stack {@code stack}参数
     * @param reference {@code reference}参数
     * @return 处理结果
     */
    private AgentDataQuery resolveReference(List<AgentDataQuery> stack, String reference) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        String value = nullable(reference);
        if (value == null || CURRENT_REFERENCES.contains(value.toLowerCase(Locale.ROOT))) {
            return stack.get(stack.size() - 1);
        }
        if (PREVIOUS_REFERENCES.contains(value.toLowerCase(Locale.ROOT))) {
            return stack.size() > 1 ? stack.get(stack.size() - 2) : null;
        }
        String explicit = value.startsWith("result:") ? value.substring("result:".length()).strip() : value;
        try {
            Long id = Long.valueOf(explicit);
            return stack.stream().filter(query -> query.getId().equals(id)).findFirst().orElse(null);
        } catch (NumberFormatException ignored) {
            // Fall through to a unique descriptive question match.
        }
        String term = descriptive(value);
        List<AgentDataQuery> matches = stack.stream()
            .filter(query -> descriptive(query.getUserQuery()).contains(term))
            .toList();
        if (matches.size() > 1) {
            throw new ServiceException(
                "结果引用不唯一，请明确选择结果 ID：" + matches.stream().map(AgentDataQuery::getId).toList(),
                HttpStatus.CONFLICT
            );
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /**
     * 处理{@code descriptive}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String descriptive(String value) {
        String result = value == null ? "" : value;
        for (String filler : List.of(
            "那张表", "这张表", "那个结果", "这个结果", "该结果", "结果",
            "刚才", "之前", "上面", "关于", "分析", "数据", "的"
        )) {
            result = result.replace(filler, "");
        }
        return result.replaceAll("[\\s，,。？！?：:]+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 处理{@code presentationView}并返回对应结果。
     *
     * @param context 待处理内容
     * @param columns {@code columns}参数
     * @param rows {@code rows}参数
     * @return 处理结果
     */
    private Map<String, Object> presentationView(
        AgentChatBIResultContext context,
        List<String> columns,
        List<List<Object>> rows
    ) {
        PortalChatBIPresentationService.ChartConfig chart = presentationService.parseChart(
            context == null ? null : context.getChartConfigJson(), columns, rows
        );
        PortalChatBIPresentationService.PivotConfig pivot = presentationService.parsePivot(
            context == null ? null : context.getPivotConfigJson(), columns, rows
        );
        Map<String, Object> value = new LinkedHashMap<>(presentationService.view(
            presentationService.materialize(columns, rows, chart, pivot)
        ));
        value.put("revision", context == null ? 1 : context.getRevisionNo());
        return value;
    }

    /**
     * 处理{@code evidenceView}并返回对应结果。
     *
     * @param evidence {@code evidence}参数
     * @return 处理结果
     */
    private Map<String, Object> evidenceView(AgentChatBIEvidence evidence) {
        if (evidence == null) {
            return Map.of("status", "missing");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("evidence_id", String.valueOf(evidence.getId()));
        value.put("type", evidence.getEvidenceType());
        value.put("producer", evidence.getProducer());
        value.put("status", evidence.getResultStatus());
        value.put("payload_digest", evidence.getPayloadDigest());
        value.put("result_hash", evidence.getResultHash());
        value.put("source_ref", evidence.getSourceRef());
        value.put("freshness", evidence.getFreshness());
        value.put("observed_at", evidence.getObservedAt());
        value.put("source_as_of", evidence.getSourceAsOf());
        value.put("expires_at", evidence.getExpiresAt());
        value.put("permission", jsonObject(evidence.getPermissionSnapshotJson()));
        value.put("detail", jsonObject(evidence.getDetailJson()));
        return value;
    }

    /**
     * 处理{@code columns}并返回对应结果。
     *
     * @param json {@code json}参数
     * @return 符合条件的数据集合
     */
    private List<String> columns(String json) {
        JsonNode node = jsonMapper.readTree(json);
        if (node == null || !node.isArray()) {
            throw new ServiceException("ChatBI 结果列快照损坏", HttpStatus.ERROR);
        }
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.isNull() ? "" : value.asText()));
        return List.copyOf(values);
    }

    /**
     * 处理{@code rows}并返回对应结果。
     *
     * @param json {@code json}参数
     * @return 符合条件的数据集合
     */
    private List<List<Object>> rows(String json) {
        JsonNode node = jsonMapper.readTree(json);
        if (node == null || !node.isArray()) {
            throw new ServiceException("ChatBI 结果行快照损坏", HttpStatus.ERROR);
        }
        List<List<Object>> values = new ArrayList<>();
        for (JsonNode row : node) {
            if (!row.isArray()) {
                throw new ServiceException("ChatBI 结果行快照格式无效", HttpStatus.ERROR);
            }
            List<Object> cells = new ArrayList<>();
            row.forEach(cell -> cells.add(jsonMapper.treeToValue(cell, Object.class)));
            values.add(Collections.unmodifiableList(new ArrayList<>(cells)));
        }
        return List.copyOf(values);
    }

    /**
     * 处理{@code jsonObject}并返回对应结果。
     *
     * @param json {@code json}参数
     * @return 处理结果
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        JsonNode node = jsonMapper.readTree(json);
        if (node == null || !node.isObject()) {
            throw new ServiceException("ChatBI 结构化快照损坏", HttpStatus.ERROR);
        }
        return jsonMapper.convertValue(node, Map.class);
    }

    /**
     * 处理{@code validJsonObject}并返回对应结果。
     *
     * @param json {@code json}参数
     * @return 处理结果
     */
    private String validJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        JsonNode node = jsonMapper.readTree(json);
        return node != null && node.isObject() ? jsonMapper.writeValueAsString(node) : "{}";
    }

    /**
     * 处理{@code boundedJson}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @return 处理结果
     */
    private String boundedJson(Map<String, Object> value, int max) {
        String json = jsonMapper.writeValueAsString(value);
        if (json.length() > max) {
            throw badRequest("ChatBI 分析上下文超过长度限制");
        }
        return json;
    }

    /**
     * 处理审计相关逻辑。
     *
     * @param principal 当前操作主体
     * @param action {@code action}参数
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param summary {@code summary}参数
     */
    private void audit(
        CurrentPrincipal principal,
        String action,
        String resourceType,
        Long resourceId,
        String summary
    ) {
        auditMapper.insertEvent(
            idGenerator.nextId(), "user", principal.id(), action, resourceType, resourceId,
            null, "success", "owner", bounded(summary, 500), LocalDateTime.now()
        );
    }

    /**
     * 校验{@code Human}，并在条件不满足时终止处理。
     *
     * @return 处理结果
     */
    private CurrentPrincipal requireHuman() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (!principal.isHuman()) {
            throw new ServiceException("机器主体不能操作 ChatBI 结果", HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    /**
     * 处理{@code displayValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String displayValue(Object value) {
        if (value == null) {
            return "空值";
        }
        String text = String.valueOf(value).replaceAll("[\\r\\n]+", " ").strip();
        return "“" + bounded(text, 500) + "”";
    }

    /**
     * 校验{@code d}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String required(String value, int max, String label) {
        String normalized = nullable(value);
        if (normalized == null || normalized.length() > max || normalized.indexOf('\0') >= 0) {
            throw badRequest(label + "为空或超过长度限制");
        }
        return normalized;
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @return 处理结果
     */
    private String bounded(String value, int max) {
        String normalized = value == null ? "" : value.replace('\0', ' ').strip();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    /**
     * 处理{@code nullable}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 判断{@code Text}是否满足要求。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean hasText(String value) {
        return nullable(value) != null;
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
     * 封装{@code Parent}相关的不可变数据。
     */
    public record ParentResult(
        Long queryId,
        Long conversationId,
        Long datasetId,
        String question,
        Map<String, Object> analysisContext,
        String sourceRef,
        LocalDateTime observedAt
    ) {
    }

    /**
     * 封装{@code PresentationUpdate}相关的不可变数据。
     */
    public record PresentationUpdate(
        Integer expectedRevision,
        PortalChatBIPresentationService.ChartConfig chart,
        PortalChatBIPresentationService.PivotConfig pivot
    ) {
    }

    /**
     * 封装{@code Drilldown}相关的不可变数据。
     */
    public record DrilldownRequest(String dimension, Object value, String question) {
    }

    /**
     * 封装{@code DrilldownTarget}相关的不可变数据。
     */
    public record DrilldownTarget(
        Long datasetId,
        Long conversationId,
        Long parentResultId,
        String question
    ) {
    }

    /**
     * 封装{@code Owned}相关的不可变数据。
     */
    private record OwnedResult(AgentDataQuery query, DataQueryStoredResultRow result) {
    }
}
