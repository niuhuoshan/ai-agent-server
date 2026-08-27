package group.aitools.nhs.platform.knowledge.service;

import group.aitools.nhs.platform.iam.domain.BusinessRelation;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeBase;
import org.springframework.stereotype.Component;

import java.util.EnumSet;

/**
 * 负责知识库授权上下文相关的转换、解析或处理逻辑。
 */
@Component
public class KnowledgeAuthorizationContextFactory {

    /**
     * 处理上下文并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param base {@code base}参数
     * @param action {@code action}参数
     * @param userInterfaceOperation 用户Interface操作参数
     * @return 处理结果
     */
    public PermissionContext context(
        CurrentPrincipal principal,
        AgentKnowledgeBase base,
        String action,
        boolean userInterfaceOperation
    ) {
        EnumSet<BusinessRelation> relations = EnumSet.noneOf(BusinessRelation.class);
        if (principal != null && base != null && principal.id().equals(base.getOwnerId())) {
            relations.add(BusinessRelation.OWNER);
        }
        if (principal != null && principal.isHuman() && base != null
            && "enterprise_shared".equals(base.getVisibility())) {
            relations.add(BusinessRelation.COLLABORATOR);
        }
        return new PermissionContext(
            "knowledge_base",
            base == null ? null : base.getId(),
            base == null ? null : base.getKnowledgeKey(),
            action,
            base != null && "active".equals(base.getStatus())
                ? ResourceState.ACTIVE : ResourceState.INACTIVE,
            userInterfaceOperation,
            relations,
            null
        );
    }

    /**
     * 创建并保存上下文。
     *
     * @return 处理结果
     */
    public PermissionContext createContext() {
        return new PermissionContext(
            "knowledge_base", null, null, "create", ResourceState.ACTIVE, true,
            java.util.Set.of(), null
        );
    }
}
