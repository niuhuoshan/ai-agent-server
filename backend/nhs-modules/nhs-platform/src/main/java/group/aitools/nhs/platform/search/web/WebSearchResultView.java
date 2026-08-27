package group.aitools.nhs.platform.search.web;

import group.aitools.nhs.platform.search.service.WebSearchClient.SearchHit;

import java.util.List;

/**
 * 封装WebSearch结果相关的不可变数据。
 */
public record WebSearchResultView(
    Long connectorId,
    String connectorName,
    String engine,
    String query,
    int resultCount,
    long latencyMs,
    List<SearchHit> results
) {
    /**
     * 创建 {@code WebSearchResultView} 实例并初始化所需依赖。
     *
     * @param connectorId 资源标识
     * @param connectorName 名称
     * @param engine {@code engine}参数
     * @param query 查询参数
     * @param resultCount 结果Count参数
     * @param latencyMs {@code latencyMs}参数
     * @param results {@code results}参数
     */
    public WebSearchResultView {
        results = List.copyOf(results);
    }
}
