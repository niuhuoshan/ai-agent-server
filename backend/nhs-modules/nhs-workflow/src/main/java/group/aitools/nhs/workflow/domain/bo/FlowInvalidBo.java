package group.aitools.nhs.workflow.domain.bo;

import jakarta.validation.constraints.NotNull;
import group.aitools.nhs.common.core.validate.AddGroup;

/**
 * 作废流程请求对象。
 *
 * @param id      流程实例 ID
 * @param comment 作废意见
 * @author may
 */
public record FlowInvalidBo(
    @NotNull(message = "流程实例id为空", groups = AddGroup.class)
    Long id,
    String comment
) {
}
