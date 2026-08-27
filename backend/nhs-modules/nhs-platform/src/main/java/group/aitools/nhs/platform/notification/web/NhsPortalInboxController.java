package group.aitools.nhs.platform.notification.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.notification.domain.AgentNotification;
import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供Nhs门户Inbox相关的 HTTP 接口，并负责请求校验与结果返回。
 * Nhs portal inbox adapter backed by the same owner-scoped notification store. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/api/portal/inbox")
public class NhsPortalInboxController {

    private final NotificationApplicationService notificationService;
    private final JsonMapper jsonMapper;

    public NhsPortalInboxController(
        NotificationApplicationService notificationService,
        JsonMapper jsonMapper
    ) {
        this.notificationService = notificationService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param limit 数量上限
     * @param offset 起始位置或序号
     * @param unreadOnly {@code unreadOnly}参数
     * @return 处理结果
     */
    @GetMapping
    public R<List<Map<String, Object>>> list(
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
        @RequestParam(defaultValue = "0") @Min(0) int offset,
        @RequestParam(name = "unread_only", defaultValue = "false") boolean unreadOnly
    ) {
        List<Map<String, Object>> items = notificationService
            .listPage(offset, limit, unreadOnly).stream().map(this::item).toList();
        return R.ok(items);
    }

    /**
     * 处理{@code unreadCount}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/unread-count")
    public R<Map<String, Object>> unreadCount() {
        return R.ok(Map.of("count", notificationService.unreadCount()));
    }

    /**
     * 处理{@code markRead}并返回对应结果。
     *
     * @param notificationId 资源标识
     * @return 处理结果
     */
    @PostMapping("/{notificationId}/read")
    public R<Map<String, Object>> markRead(@PathVariable @Positive Long notificationId) {
        notificationService.markRead(notificationId);
        return R.ok(Map.of("status", "success"));
    }

    /**
     * 处理{@code markAllRead}并返回对应结果。
     *
     * @return 处理结果
     */
    @PostMapping("/read-all")
    public R<Map<String, Object>> markAllRead() {
        return R.ok(Map.of("updated", notificationService.markAllRead()));
    }

    /**
     * 删除{@code Read}。
     *
     * @return 处理结果
     */
    @DeleteMapping("/read")
    public R<Map<String, Object>> deleteRead() {
        return R.ok(Map.of("deleted", notificationService.deleteRead()));
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

    /**
     * 处理{@code item}并返回对应结果。
     *
     * @param notification 通知参数
     * @return 处理结果
     */
    private Map<String, Object> item(AgentNotification notification) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", notification.getId());
        value.put("category", notification.getCategory());
        value.put("level", notification.getLevel());
        value.put("title", notification.getTitle());
        value.put("content", notification.getContent());
        value.put("resource_type", notification.getResourceType());
        value.put("resource_id", notification.getResourceId());
        value.put("metadata", parseMetadata(notification.getMetadataJson()));
        value.put("read_at", notification.getReadAt());
        value.put("created_at", notification.getCreatedAt());
        return value;
    }

    /**
     * 处理parse元数据并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Object parseMetadata(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(value, Object.class);
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }
}
