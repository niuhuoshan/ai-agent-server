package group.aitools.nhs.workflow.liteflow.complete;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeBooleanComponent;
import group.aitools.nhs.workflow.common.ConditionalOnEnable;
import group.aitools.nhs.workflow.domain.context.CompleteTaskContext;

/**
 * 判断办理任务后是否进入自动审批分支。
 *
 * @author may
 */
@ConditionalOnEnable
@LiteflowComponent("completeNeedAutoPass")
public class CompleteNeedAutoPassComponent extends NodeBooleanComponent {

    @Override
    public boolean processBoolean() {
        CompleteTaskContext context = getContextBean(CompleteTaskContext.class);
        return Boolean.TRUE.equals(context.getAutoPass());
    }

}
