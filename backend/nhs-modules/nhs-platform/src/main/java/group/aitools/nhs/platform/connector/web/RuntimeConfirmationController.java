package group.aitools.nhs.platform.connector.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import group.aitools.nhs.platform.connector.service.RuntimeConfirmationApplicationService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.domain.R;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供运行时Confirmation相关的 HTTP 接口，并负责请求校验与结果返回。
 * Owner-bound API for the AgentScope business-confirmation card. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/runtime-confirmations")
public class RuntimeConfirmationController {

    private final RuntimeConfirmationApplicationService service;

    public RuntimeConfirmationController(RuntimeConfirmationApplicationService service) {
        this.service = service;
    }

    /**
     * 获取{@code get}。
     *
     * @param confirmationKey {@code confirmationKey}参数
     * @return 处理结果
     */
    @GetMapping("/{confirmationKey}")
    public R<RuntimeConfirmationView> get(
        @PathVariable @NotBlank String confirmationKey
    ) {
        return R.ok(service.get(confirmationKey));
    }

    /**
     * 处理{@code confirm}并返回对应结果。
     *
     * @param confirmationKey {@code confirmationKey}参数
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{confirmationKey}/confirm")
    public R<RuntimeConfirmationDecisionResult> confirm(
        @PathVariable @NotBlank String confirmationKey,
        @Valid @RequestBody RuntimeConfirmationDecisionRequest request
    ) {
        return response(service.confirm(confirmationKey, request));
    }

    /**
     * 判断{@code cel}是否满足要求。
     *
     * @param confirmationKey {@code confirmationKey}参数
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{confirmationKey}/cancel")
    public R<RuntimeConfirmationDecisionResult> cancel(
        @PathVariable @NotBlank String confirmationKey,
        @Valid @RequestBody RuntimeConfirmationDecisionRequest request
    ) {
        return response(service.cancel(confirmationKey, request));
    }

    /**
     * 处理{@code response}并返回对应结果。
     *
     * @param result 结果参数
     * @return 处理结果
     */
    private R<RuntimeConfirmationDecisionResult> response(
        RuntimeConfirmationDecisionResult result
    ) {
        if (result != null && result.confirmation() != null
            && "expired".equals(result.confirmation().status())) {
            throw new ServiceException("业务确认已过期", HttpStatus.CONFLICT);
        }
        return R.ok(result);
    }
}
