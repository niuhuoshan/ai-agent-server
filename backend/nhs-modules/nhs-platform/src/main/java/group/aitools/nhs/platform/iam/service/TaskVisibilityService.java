package group.aitools.nhs.platform.iam.service;

import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.TaskVisibility;

/**
 * 处理{@code authorizeView}并返回对应结果。
 *
 * 定义任务Visibility相关的业务服务契约。
 * Separates task readability from task execution permissions. */
public interface TaskVisibilityService {

    AuthorizationDecision authorizeView(
        CurrentPrincipal principal,
        Long taskId,
        Long artifactId,
        TaskVisibility visibility
    );
}
