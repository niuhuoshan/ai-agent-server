package group.aitools.nhs.platform.workflow.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import group.aitools.nhs.platform.workflow.service.WorkflowCatalogService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供平台工作流相关的 HTTP 接口，并负责请求校验与结果返回。
 */
@SaCheckLogin
@RestController
@RequestMapping("/platform/workflows")
public class PlatformWorkflowController {

    private final WorkflowCatalogService service;

    /**
     * 创建 {@code PlatformWorkflowController} 实例并初始化所需依赖。
     *
     * @param service {@code service}参数
     */
    public PlatformWorkflowController(WorkflowCatalogService service) {
        this.service = service;
    }

    /**
     * 查询{@code list}列表。
     *
     * @return 处理结果
     */
    @GetMapping
    public R<List<WorkflowTemplateView>> list() {
        return R.ok(service.list());
    }
}
