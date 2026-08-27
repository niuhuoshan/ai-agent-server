package group.aitools.nhs.platform.nhs.portal.chatbi;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提供门户对话BIMonitor相关的 HTTP 接口，并负责请求校验与结果返回。
 * ChatBI result monitor endpoint; SQL is optional only for server-side result integrations. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/api/portal/chatbi-monitors")
public class PortalChatBIMonitorController {

    private final PortalChatBIMonitorService service;

    public PortalChatBIMonitorController(PortalChatBIMonitorService service) {
        this.service = service;
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping({"", "/"})
    public R<Map<String, Object>> create(@Valid @RequestBody CreateMonitorRequest request) {
        return R.ok(service.create(request.toServiceRequest()));
    }

    /**
     * 封装{@code CreateMonitor}相关的不可变数据。
     */
    public record CreateMonitorRequest(
        @NotBlank @Size(max = 128) String result_id,
        @Size(max = 255) String title,
        String schedule_type,
        String time_value,
        Integer weekday,
        Integer monthday,
        boolean notify_on_success
    ) {
        /**
         * 将输入数据转换为{@code ServiceRequest}。
         *
         * @return 处理结果
         */
        PortalChatBIMonitorService.CreateMonitorRequest toServiceRequest() {
            return new PortalChatBIMonitorService.CreateMonitorRequest(
                result_id, title, schedule_type,
                time_value, weekday, monthday, notify_on_success
            );
        }
    }
}
