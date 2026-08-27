package group.aitools.nhs.platform.provider;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeBase;
import group.aitools.nhs.platform.knowledge.web.KnowledgeRetrievalView;

/**
 * 处理提供方Type并返回对应结果。
 *
 * 定义External知识库相关的处理能力契约。
 * Optional boundary for external retrieval engines such as RAGFlow. */
public interface ExternalKnowledgeProvider {

    String providerType();

    /**
     * 处理{@code retrieve}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param knowledgeBase 知识库Base参数
     * @param query 查询参数
     * @param topK {@code topK}参数
     * @return 处理结果
     */
    KnowledgeRetrievalView retrieve(
        CurrentPrincipal principal,
        AgentKnowledgeBase knowledgeBase,
        String query,
        int topK
    );
}
