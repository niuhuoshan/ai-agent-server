package group.aitools.nhs.platform.notification.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.notification.service.NotificationDeliveryOutboxService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供Nhs门户通知Delivery相关的 HTTP 接口，并负责请求校验与结果返回。
 * Owner-scoped Nhs-compatible external notification delivery history. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/api/portal/notifications/deliveries")
public class NhsPortalNotificationDeliveryController {

    private final NotificationDeliveryOutboxService service;

    public NhsPortalNotificationDeliveryController(NotificationDeliveryOutboxService service) {
        this.service = service;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<NotificationDeliveryView>> list(
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        return R.ok(service.list(limit));
    }

    /**
     * 处理{@code retry}并返回对应结果。
     *
     * @param deliveryId 资源标识
     * @return 处理结果
     */
    @PostMapping("/{deliveryId}/retry")
    public R<NotificationDeliveryView> retry(@PathVariable @Positive Long deliveryId) {
        return R.ok(service.retry(deliveryId));
    }
}
