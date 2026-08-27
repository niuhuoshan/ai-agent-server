package group.aitools.nhs.platform.runtime.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import group.aitools.nhs.runtime.spi.AgentRuntime;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供平台运行时相关的 HTTP 接口，并负责请求校验与结果返回。
 * Read-only runtime readiness endpoint for private-deployment diagnostics. */
@SaCheckLogin
@RestController
@RequestMapping("/platform/runtime")
public class PlatformRuntimeController {

    private final ObjectProvider<AgentRuntime> runtimeProvider;

    public PlatformRuntimeController(ObjectProvider<AgentRuntime> runtimeProvider) {
        this.runtimeProvider = runtimeProvider;
    }

    /**
     * 处理{@code status}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/status")
    public R<RuntimeStatusView> status() {
        AgentRuntime runtime = runtimeProvider.getIfAvailable();
        if (runtime == null) {
            return R.ok(new RuntimeStatusView(
                "agentscope_java", false, "disabled",
                "AgentScope 运行时未启用，请设置 NHS_RUNTIME_AGENTSCOPE_ENABLED=true 并配置模型凭证"
            ));
        }
        return R.ok(new RuntimeStatusView(
            runtime.getClass().getSimpleName(), true, "loaded", "Agent 运行时已加载，模型凭证按请求解析"
        ));
    }
}
