package group.aitools.nhs.platform.runtime.question.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.runtime.question.service.RuntimeUserQuestionApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供平台运行时用户追问相关的 HTTP 接口，并负责请求校验与结果返回。
 * Current human user's Agent-initiated question cards. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/runtime-user-questions")
public class PlatformRuntimeUserQuestionController {

    private final RuntimeUserQuestionApplicationService service;

    public PlatformRuntimeUserQuestionController(RuntimeUserQuestionApplicationService service) {
        this.service = service;
    }

    /**
     * 获取{@code get}。
     *
     * @param questionId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{questionId}")
    public R<UserQuestionView> get(@PathVariable @NotBlank String questionId) {
        return R.ok(service.get(questionId));
    }

    /**
     * 处理{@code pending}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/pending")
    public R<List<UserQuestionView>> pending(
        @RequestParam @Positive Long conversationId,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return R.ok(service.pending(conversationId, limit));
    }

    /**
     * 处理{@code answer}并返回对应结果。
     *
     * @param questionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{questionId}/answer")
    public R<UserQuestionDecisionResult> answer(
        @PathVariable @NotBlank String questionId,
        @Valid @RequestBody UserQuestionAnswerRequest request
    ) {
        return R.ok(service.answer(questionId, request));
    }

    /**
     * 判断{@code cel}是否满足要求。
     *
     * @param questionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{questionId}/cancel")
    public R<UserQuestionDecisionResult> cancel(
        @PathVariable @NotBlank String questionId,
        @Valid @RequestBody UserQuestionCancelRequest request
    ) {
        return R.ok(service.cancel(questionId, request));
    }
}
