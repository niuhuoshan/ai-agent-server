package group.aitools.nhs.platform.iam.service;

import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;

/**
 * 处理{@code authorize}并返回对应结果。
 *
 * 定义授权相关的业务服务契约。
 * Single authorization entry point for platform controllers and gateways. */
public interface AuthorizationService {

    AuthorizationDecision authorize(CurrentPrincipal principal, PermissionContext context);
}
