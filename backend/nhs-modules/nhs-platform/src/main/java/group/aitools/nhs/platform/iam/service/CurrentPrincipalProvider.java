package group.aitools.nhs.platform.iam.service;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;

/**
 * 处理当前操作主体并返回对应结果。
 *
 * 定义当前操作主体相关的处理能力契约。
 * Supplies an organization-independent principal for the current request. */
public interface CurrentPrincipalProvider {

    CurrentPrincipal currentPrincipal();
}
