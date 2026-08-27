package group.aitools.nhs.platform.automation.web;

import group.aitools.nhs.platform.automation.service.WebhookAutomationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供平台回调通知相关的 HTTP 接口，并负责请求校验与结果返回。
 */
@RestController
@RequestMapping("/open/platform/webhooks")
public class PlatformWebhookController {

    private final WebhookAutomationService service;

    /**
     * 创建 {@code PlatformWebhookController} 实例并初始化所需依赖。
     *
     * @param service {@code service}参数
     */
    public PlatformWebhookController(WebhookAutomationService service) {
        this.service = service;
    }

    /**
     * 执行{@code invoke}相关的处理流程。
     *
     * @param triggerKey {@code triggerKey}参数
     * @param authorization 授权参数
     * @param timestamp {@code timestamp}参数
     * @param nonce {@code nonce}参数
     * @param signature {@code signature}参数
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param body {@code body}参数
     * @return 处理结果
     */
    @PostMapping("/{triggerKey}")
    public R<AutomationFireView> invoke(
        @PathVariable String triggerKey,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader("X-Agent-Timestamp") String timestamp,
        @RequestHeader("X-Agent-Nonce") String nonce,
        @RequestHeader("X-Agent-Signature") String signature,
        @RequestHeader("X-Agent-Idempotency-Key") String idempotencyKey,
        @RequestBody(required = false) String body
    ) {
        return R.ok(service.invoke(
            triggerKey, authorization, timestamp, nonce, signature, idempotencyKey, body
        ));
    }
}
