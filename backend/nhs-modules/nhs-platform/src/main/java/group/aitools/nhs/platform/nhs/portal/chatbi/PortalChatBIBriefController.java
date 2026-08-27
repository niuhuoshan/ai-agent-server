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
 * 提供门户对话BIBrief相关的 HTTP 接口，并负责请求校验与结果返回。
 * ChatBI business brief endpoint. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/api/portal/chatbi-briefs")
public class PortalChatBIBriefController {

    private final PortalChatBIBriefService service;

    public PortalChatBIBriefController(PortalChatBIBriefService service) {
        this.service = service;
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping({"", "/"})
    public R<Map<String, Object>> create(@Valid @RequestBody CreateBriefRequest request) {
        return R.ok(service.create(request.toServiceRequest()));
    }

    /**
     * 封装{@code CreateBrief}相关的不可变数据。
     */
    public record CreateBriefRequest(
        @NotBlank @Size(max = 128) String result_id,
        boolean export_word,
        boolean polish_with_llm,
        @Size(max = 255) String title
    ) {
        /**
         * 将输入数据转换为{@code ServiceRequest}。
         *
         * @return 处理结果
         */
        PortalChatBIBriefService.CreateBriefRequest toServiceRequest() {
            return new PortalChatBIBriefService.CreateBriefRequest(
                result_id, export_word, polish_with_llm, title
            );
        }
    }
}
