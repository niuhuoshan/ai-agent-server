package group.aitools.nhs.platform.search.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import group.aitools.nhs.platform.search.service.WebSearchApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供平台WebSearch相关的 HTTP 接口，并负责请求校验与结果返回。
 */
@Validated
@SaCheckLogin
@RestController
@RequestMapping({"/platform/search", "/api/portal/search"})
public class PlatformWebSearchController {

    private final WebSearchApplicationService service;

    /**
     * 创建 {@code PlatformWebSearchController} 实例并初始化所需依赖。
     *
     * @param service {@code service}参数
     */
    public PlatformWebSearchController(WebSearchApplicationService service) {
        this.service = service;
    }

    /**
     * 处理{@code providers}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/providers")
    public R<List<SearchProviderView>> providers() {
        return R.ok(service.providers());
    }

    /**
     * 获取查询。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/query")
    public R<WebSearchResultView> query(@Valid @RequestBody WebSearchRequest request) {
        return R.ok(service.preview(request));
    }
}
