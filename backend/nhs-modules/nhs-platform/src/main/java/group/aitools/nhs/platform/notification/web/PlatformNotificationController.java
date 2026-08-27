package group.aitools.nhs.platform.notification.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供平台通知相关的 HTTP 接口，并负责请求校验与结果返回。
 * Current human user's notification inbox. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/notifications")
public class PlatformNotificationController {

    private final NotificationApplicationService notificationService;

    public PlatformNotificationController(NotificationApplicationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param category {@code category}参数
     * @param unreadOnly {@code unreadOnly}参数
     * @param beforeId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<NotificationView>> list(
        @RequestParam(required = false)
        @Pattern(regexp = "task|approval|run|artifact|acceptance|system") String category,
        @RequestParam(defaultValue = "false") boolean unreadOnly,
        @RequestParam(required = false) @Positive Long beforeId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        return R.ok(notificationService.list(category, unreadOnly, beforeId, limit));
    }

    /**
     * 处理{@code unreadCount}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/unread-count")
    public R<Long> unreadCount() {
        return R.ok(notificationService.unreadCount());
    }

    /**
     * 处理{@code markRead}并返回对应结果。
     *
     * @param notificationId 资源标识
     * @return 处理结果
     */
    @PostMapping("/{notificationId}/read")
    public R<NotificationView> markRead(@PathVariable @Positive Long notificationId) {
        return R.ok(notificationService.markRead(notificationId));
    }

    /**
     * 处理{@code markAllRead}并返回对应结果。
     *
     * @return 处理结果
     */
    @PostMapping("/read-all")
    public R<Integer> markAllRead() {
        return R.ok(notificationService.markAllRead());
    }

    /**
     * 删除{@code Read}。
     *
     * @return 处理结果
     */
    @DeleteMapping("/read")
    public R<Integer> deleteRead() {
        return R.ok(notificationService.deleteRead());
    }

    /**
     * 删除{@code One}。
     *
     * @param notificationId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/{notificationId}")
    public R<Void> deleteOne(@PathVariable @Positive Long notificationId) {
        notificationService.deleteOne(notificationId);
        return R.ok();
    }
}
