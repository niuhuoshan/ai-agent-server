package group.aitools.nhs.platform.iam.service;

import group.aitools.nhs.platform.audit.service.AuthorizationAuditService;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

/**
 * 表示授权Enforcer相关的领域对象。
 * Executes, audits and enforces one authorization decision. */
@Service
public class AuthorizationEnforcer {

    private final AuthorizationService authorizationService;
    private final AuthorizationAuditService auditService;

    public AuthorizationEnforcer(
        AuthorizationService authorizationService,
        AuthorizationAuditService auditService
    ) {
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    /**
     * 校验{@code Allowed}，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param context 待处理内容
     * @return 处理结果
     */
    public AuthorizationDecision requireAllowed(CurrentPrincipal principal, PermissionContext context) {
        AuthorizationDecision decision = decide(principal, context);
        if (!decision.allowed()) {
            throw new ServiceException("没有权限执行此操作：" + decision.reasonCode(), HttpStatus.FORBIDDEN);
        }
        return decision;
    }

    /**
     * 处理{@code decide}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param context 待处理内容
     * @return 处理结果
     */
    public AuthorizationDecision decide(CurrentPrincipal principal, PermissionContext context) {
        AuthorizationDecision decision = authorizationService.authorize(principal, context);
        auditService.record(principal, context, decision);
        return decision;
    }
}
