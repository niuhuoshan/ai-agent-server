package group.aitools.nhs.platform.nhs.portal.example;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 负责门户Example运行时Search相关的业务编排与领域规则处理。
 * Runtime retrieval over approved, locally synchronized examples. */
@Service
public class PortalExampleRuntimeSearchService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;
    private final AgentChatBIExampleMapper mapper;

    public PortalExampleRuntimeSearchService(AgentChatBIExampleMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 查询{@code search}列表。
     *
     * @param request 请求参数
     * @param query 查询参数
     * @param datasetId 资源标识
     * @param topK {@code topK}参数
     * @return 处理结果
     */
    public Map<String, Object> search(
        AgentRunRequest request,
        String query,
        Long datasetId,
        Integer topK
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Objects.requireNonNull(request, "request must not be null");
        String normalizedQuery = requiredQuery(query);
        int limit = topK == null ? DEFAULT_TOP_K : topK;
        if (limit < 1 || limit > MAX_TOP_K) {
            throw new ServiceException("topK 必须在 1 到 20 之间", HttpStatus.BAD_REQUEST);
        }
        List<Long> allowedDatasets = allowedDatasets(request);
        if (allowedDatasets.isEmpty()) {
            throw new ServiceException("当前运行快照没有可检索的数据集授权", HttpStatus.FORBIDDEN);
        }
        if (datasetId != null && !allowedDatasets.contains(datasetId)) {
            throw new ServiceException("指定数据集不在当前 Agent 运行授权范围内", HttpStatus.FORBIDDEN);
        }
        List<AgentChatBIExample> rows = mapper.selectRuntimeCandidates(
            allowedDatasets, datasetId, normalizedQuery, limit
        );
        List<Map<String, Object>> items = new ArrayList<>();
        for (AgentChatBIExample row : rows) {
            mapper.incrementUseCount(row.getId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("trace_id", row.getTraceId());
            item.put("dataset_id", row.getDatasetId());
            item.put("question", row.getRefinedQuery() == null
                ? row.getUserQuery() : row.getRefinedQuery());
            item.put("raw_query", row.getUserQuery());
            item.put("context_summary", row.getContextSummary());
            item.put("sql", row.getSqlText());
            item.put("sql_metadata", row.getSqlMetadataJson());
            item.put("category", row.getCategory());
            item.put("review_status", row.getReviewStatus());
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("index", "relational");
        result.put("query", normalizedQuery);
        result.put("dataset_id", datasetId);
        result.put("top_k", limit);
        result.put("items", items);
        return result;
    }

    /**
     * 处理{@code allowedDatasets}并返回对应结果。
     *
     * @param request 请求参数
     * @return 符合条件的数据集合
     */
    private List<Long> allowedDatasets(AgentRunRequest request) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Object rawSnapshot = request.attributes().get("taskResourceSnapshot");
        if (!(rawSnapshot instanceof Map<?, ?> snapshot)
            || !(snapshot.get("resources") instanceof List<?> resources)) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (Object raw : resources) {
            if (!(raw instanceof Map<?, ?> resource)
                || !"dataset".equals(String.valueOf(resource.get("resourceType")))) {
                continue;
            }
            Object rawId = resource.get("resourceId");
            Object permission = resource.get("permission");
            if (!(rawId instanceof Number number)
                || number.longValue() <= 0
                || number.doubleValue() != number.longValue()
                || !List.of("read", "query", "admin").contains(String.valueOf(permission))) {
                continue;
            }
            Long id = number.longValue();
            if (!result.contains(id)) {
                result.add(id);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 校验查询，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String requiredQuery(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > 16_384 || normalized.indexOf('\0') >= 0) {
            throw new ServiceException("案例检索问题为空或超过长度限制", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }
}
