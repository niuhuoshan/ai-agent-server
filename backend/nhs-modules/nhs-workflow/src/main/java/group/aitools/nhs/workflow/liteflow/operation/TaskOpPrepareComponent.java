package group.aitools.nhs.workflow.liteflow.operation;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.extern.slf4j.Slf4j;
import group.aitools.nhs.common.core.exception.ServiceException;
import group.aitools.nhs.common.core.utils.ValidatorUtils;
import group.aitools.nhs.common.core.validate.AddGroup;
import group.aitools.nhs.common.core.validate.EditGroup;
import group.aitools.nhs.common.satoken.utils.LoginHelper;
import org.dromara.warm.flow.core.dto.FlowParams;
import group.aitools.nhs.workflow.common.ConditionalOnEnable;
import group.aitools.nhs.workflow.common.enums.TaskOperationEnum;
import group.aitools.nhs.workflow.domain.bo.TaskOperationBo;
import group.aitools.nhs.workflow.domain.context.TaskOperationContext;

import java.util.Collections;

/**
 * 准备任务操作参数。
 *
 * @author may
 */
@ConditionalOnEnable
@Slf4j
@LiteflowComponent("taskOpPrepare")
public class TaskOpPrepareComponent extends NodeComponent {

    @Override
    public void process() {
        // 以下流程根据当前类型或状态选择处理分支，并保证每种分支返回明确结果。
        TaskOperationContext context = getContextBean(TaskOperationContext.class);
        TaskOperationEnum op = TaskOperationEnum.getByCode(context.getTaskOperation());
        if (op == null) {
            log.error("Invalid operation type:{} ", context.getTaskOperation());
            throw new ServiceException("Invalid operation type " + context.getTaskOperation());
        }
        context.setOperation(op);

        TaskOperationBo bo = context.getTaskOperationBo();
        switch (op) {
            case DELEGATE_TASK, TRANSFER_TASK -> ValidatorUtils.validate(bo, AddGroup.class);
            case ADD_SIGNATURE, REDUCTION_SIGNATURE -> ValidatorUtils.validate(bo, EditGroup.class);
        }

        FlowParams flowParams = FlowParams.build().message(bo.getMessage());
        if (LoginHelper.isSuperAdmin()) {
            flowParams.ignore(true);
        }
        switch (op) {
            case DELEGATE_TASK, TRANSFER_TASK -> flowParams.addHandlers(Collections.singletonList(bo.getUserId()));
            case ADD_SIGNATURE -> flowParams.addHandlers(bo.getUserIds());
            case REDUCTION_SIGNATURE -> flowParams.reductionHandlers(bo.getUserIds());
        }
        context.setFlowParams(flowParams);
    }

}
