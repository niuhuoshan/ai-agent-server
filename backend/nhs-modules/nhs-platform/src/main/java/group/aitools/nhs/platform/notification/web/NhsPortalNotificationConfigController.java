package group.aitools.nhs.platform.notification.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import group.aitools.nhs.platform.notification.service.UserNotificationConfigService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提供Nhs门户通知Config相关的 HTTP 接口，并负责请求校验与结果返回。
 * Nhs-compatible personal notification channel configuration and test-send routes. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/api/portal/notifications")
public class NhsPortalNotificationConfigController {

    private final UserNotificationConfigService service;

    public NhsPortalNotificationConfigController(UserNotificationConfigService service) {
        this.service = service;
    }

    /**
     * 处理{@code config}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/config")
    public R<Map<String, Map<String, Object>>> config() {
        return R.ok(service.configs());
    }

    /**
     * 保存{@code save}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/config")
    public R<Map<String, Object>> save(@Valid @RequestBody NotificationConfigRequest request) {
        return R.ok(service.save(request.channelType(), request.configData()));
    }

    /**
     * 处理{@code test}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/test")
    public R<Map<String, Object>> test(@Valid @RequestBody NotificationConfigRequest request) {
        return R.ok(service.test(request.channelType(), request.configData()));
    }

    /**
     * 封装通知Config相关的不可变数据。
     */
    public record NotificationConfigRequest(
        @JsonProperty("channel_type")
        @NotBlank
        @Pattern(regexp = "dingtalk|wechat_work|email")
        String channelType,
        @JsonProperty("config_data") @NotNull Map<String, Object> configData
    ) {
    }
}
