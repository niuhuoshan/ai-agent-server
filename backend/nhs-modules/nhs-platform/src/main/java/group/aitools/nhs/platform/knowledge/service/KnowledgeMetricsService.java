package group.aitools.nhs.platform.knowledge.service;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.knowledge.mapper.KnowledgeMetricsMapper;
import group.aitools.nhs.platform.knowledge.web.KnowledgeBaseView;
import group.aitools.nhs.platform.knowledge.web.KnowledgeMetricsView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责知识库Metrics相关的业务编排与领域规则处理。
 * Builds permission-filtered operational metrics from durable local retrieval facts. */
@Service
public class KnowledgeMetricsService {

    private static final int MAX_DAYS = 90;

    private final CurrentPrincipalProvider principalProvider;
    private final KnowledgeApplicationService knowledgeService;
    private final KnowledgeMetricsMapper metricsMapper;

    public KnowledgeMetricsService(
        CurrentPrincipalProvider principalProvider,
        KnowledgeApplicationService knowledgeService,
        KnowledgeMetricsMapper metricsMapper
    ) {
        this.principalProvider = principalProvider;
        this.knowledgeService = knowledgeService;
        this.metricsMapper = metricsMapper;
    }

    /**
     * 处理{@code summary}并返回对应结果。
     *
     * @param days {@code days}参数
     * @param startDate {@code startDate}参数
     * @param endDate {@code endDate}参数
     * @return 处理结果
     */
    public KnowledgeMetricsView summary(int days, String startDate, String endDate) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        DateRange range = range(days, startDate, endDate);
        List<Long> baseIds = knowledgeService.list(null, true, 500).stream()
            .map(KnowledgeBaseView::id)
            .toList();
        List<Long> userIds = principal.hasRole(PlatformRole.PLATFORM_ADMIN)
            ? metricsMapper.selectActiveUserIds()
            : List.of(principal.id());
        if (userIds.isEmpty()) {
            userIds = List.of(principal.id());
        }

        Map<String, Object> summary = emptySummary();
        Map<String, Object> corpus = emptyCorpus();
        List<Map<String, Object>> trend = emptyTrend(range);
        List<Map<String, Object>> bases = List.of();
        if (!baseIds.isEmpty()) {
            summary.putAll(metricsMapper.selectSummary(range.from(), range.to(), userIds));
            corpus.putAll(metricsMapper.selectDocumentStats(baseIds));
            trend = mergeTrend(range, metricsMapper.selectDailyTrend(range.from(), range.to(), userIds));
            bases = metricsMapper.selectBaseStats(range.from(), range.to(), userIds, baseIds);
        }
        long retrievals = longValue(summary.get("retrieval_count"));
        long citations = longValue(summary.get("citation_count"));
        summary.put("citation_rate", retrievals == 0 ? 0D : round(citations * 100D / retrievals));
        summary.put("scope", principal.hasRole(PlatformRole.PLATFORM_ADMIN) ? "enterprise" : "self");
        summary.put("accessible_knowledge_bases", baseIds.size());
        return new KnowledgeMetricsView(
            baseIds.isEmpty() ? "empty" : "ok",
            "agent_knowledge_retrieval_event",
            range.start().toString(),
            range.end().toString(),
            Map.copyOf(summary),
            Map.copyOf(corpus),
            List.copyOf(trend),
            List.copyOf(bases)
        );
    }

    /**
     * 处理{@code range}并返回对应结果。
     *
     * @param days {@code days}参数
     * @param startDate {@code startDate}参数
     * @param endDate {@code endDate}参数
     * @return 处理结果
     */
    private DateRange range(int days, String startDate, String endDate) {
        try {
            LocalDate end;
            LocalDate start;
            if (startDate != null && !startDate.isBlank() && endDate != null && !endDate.isBlank()) {
                start = LocalDate.parse(startDate);
                end = LocalDate.parse(endDate);
            } else {
                int boundedDays = Math.max(1, Math.min(days, MAX_DAYS));
                end = LocalDate.now();
                start = end.minusDays(boundedDays - 1L);
            }
            if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
                throw new ServiceException("知识指标时间范围无效或超过90天", HttpStatus.BAD_REQUEST);
            }
            return new DateRange(start, end, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        } catch (DateTimeParseException exception) {
            throw new ServiceException("知识指标日期格式必须为 YYYY-MM-DD", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 处理{@code mergeTrend}并返回对应结果。
     *
     * @param range {@code range}参数
     * @param rows {@code rows}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> mergeTrend(DateRange range, List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byDay = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object value = row.get("day");
            if (value != null) {
                byDay.put(String.valueOf(value), row);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (LocalDate day = range.start(); !day.isAfter(range.end()); day = day.plusDays(1)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", day.toString());
            row.put("retrieval_count", 0L);
            row.put("empty_count", 0L);
            row.put("failed_count", 0L);
            row.put("citation_count", 0L);
            row.putAll(byDay.getOrDefault(day.toString(), Map.of()));
            result.add(row);
        }
        return result;
    }

    /**
     * 处理{@code emptyTrend}并返回对应结果。
     *
     * @param range {@code range}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> emptyTrend(DateRange range) {
        return mergeTrend(range, List.of());
    }

    /**
     * 处理{@code emptySummary}并返回对应结果。
     *
     * @return 处理结果
     */
    private Map<String, Object> emptySummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("retrieval_count", 0L);
        result.put("empty_count", 0L);
        result.put("failed_count", 0L);
        result.put("citation_count", 0L);
        result.put("average_latency_ms", 0D);
        return result;
    }

    /**
     * 处理{@code emptyCorpus}并返回对应结果。
     *
     * @return 处理结果
     */
    private Map<String, Object> emptyCorpus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document_count", 0L);
        result.put("ready_document_count", 0L);
        result.put("failed_document_count", 0L);
        result.put("chunk_count", 0L);
        return result;
    }

    /**
     * 处理{@code longValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * 处理{@code round}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private double round(double value) {
        return Math.round(value * 100D) / 100D;
    }

    /**
     * 封装{@code DateRange}相关的不可变数据。
     */
    private record DateRange(
        LocalDate start,
        LocalDate end,
        LocalDateTime from,
        LocalDateTime to
    ) {
    }
}
