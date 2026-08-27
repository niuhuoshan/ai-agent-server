package group.aitools.nhs.runtime.spi;

import java.util.List;

/**
 * 定义运行时知识库相关的处理能力契约。
 */
public interface RuntimeKnowledgeProvider {

    /**
     * 获取{@code resolve}。
     *
     * @param request 请求参数
     * @return 符合条件的数据集合
     */
    List<RuntimeKnowledgeDefinition> resolve(AgentRunRequest request);

    /**
     * 查询{@code search}列表。
     *
     * @param request 请求参数
     * @param knowledgeBaseId 资源标识
     * @param query 查询参数
     * @param topK {@code topK}参数
     * @return 处理结果
     */
    Object search(AgentRunRequest request, Long knowledgeBaseId, String query, Integer topK);

    /**
     * 处理{@code empty}并返回对应结果。
     *
     * @return 处理结果
     */
    static RuntimeKnowledgeProvider empty() {
        return new RuntimeKnowledgeProvider() {
            /**
             * 获取{@code resolve}。
             *
             * @param request 请求参数
             * @return 符合条件的数据集合
             */
            @Override
            public List<RuntimeKnowledgeDefinition> resolve(AgentRunRequest request) {
                return List.of();
            }

            /**
             * 查询{@code search}列表。
             *
             * @param request 请求参数
             * @param knowledgeBaseId 资源标识
             * @param query 查询参数
             * @param topK {@code topK}参数
             * @return 处理结果
             */
            @Override
            public Object search(
                AgentRunRequest request, Long knowledgeBaseId, String query, Integer topK
            ) {
                throw new IllegalStateException("runtime knowledge provider is not configured");
            }
        };
    }
}
