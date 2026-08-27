package group.aitools.nhs.platform.knowledge.web;

import java.util.List;
import java.util.Map;

/**
 * 封装知识库Metrics相关的不可变数据。
 * Privacy-preserving operational metrics for local knowledge retrieval. */
public record KnowledgeMetricsView(
    String status,
    String source,
    String periodStart,
    String periodEnd,
    Map<String, Object> summary,
    Map<String, Object> corpus,
    List<Map<String, Object>> dailyTrend,
    List<Map<String, Object>> knowledgeBases
) {
}
