package group.aitools.nhs.platform.nhs.portal.prompt;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 提供门户提示词相关的 HTTP 接口，并负责请求校验与结果返回。
 * Nhs prompt studio routes backed by Agent versions. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/api/portal/prompts")
public class PortalPromptController {

    private final PortalPromptService service;
    private final PromptAuditService auditService;

    public PortalPromptController(PortalPromptService service, PromptAuditService auditService) {
        this.service = service;
        this.auditService = auditService;
    }

    /**
     * 查询{@code list}列表。
     *
     * @return 处理结果
     */
    @GetMapping({"", "/"})
    public R<List<Map<String, Object>>> list() {
        return R.ok(service.list());
    }

    /**
     * 处理{@code detail}并返回对应结果。
     *
     * @param source 数据源参数
     * @param targetId 资源标识
     * @param version 版本参数
     * @return 处理结果
     */
    @GetMapping("/detail")
    public R<Map<String, Object>> detail(
        @RequestParam String source,
        @RequestParam(name = "target_id") String targetId,
        @RequestParam(required = false) Integer version
    ) {
        return R.ok(service.detail(source, targetId, version));
    }

    /**
     * 处理历史记录并返回对应结果。
     *
     * @param source 数据源参数
     * @param targetId 资源标识
     * @return 处理结果
     */
    @GetMapping("/history")
    public R<List<Map<String, Object>>> history(
        @RequestParam String source,
        @RequestParam(name = "target_id") String targetId
    ) {
        return R.ok(service.history(source, targetId));
    }

    /**
     * 保存{@code save}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/save")
    public R<Map<String, Object>> save(@Valid @RequestBody SavePromptRequest request) {
        PortalPromptService.SaveResult result = service.save(
            request.source(), request.targetId(), request.content()
        );
        auditService.recordSave(request.source(), request.targetId(), result, request.content());
        if (!result.changed()) {
            return R.ok("No changes detected", Map.of("status", "unchanged", "version_number", result.versionNumber()));
        }
        return R.ok(Map.of("status", "success", "version_number", result.versionNumber()));
    }

    /**
     * 处理{@code restore}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/restore")
    public R<Map<String, Object>> restore(@Valid @RequestBody RestorePromptRequest request) {
        PortalPromptService.RestoreResult result = service.restore(
            request.source(), request.targetId(), request.versionNumber()
        );
        auditService.recordRestore(request.source(), request.targetId(), result);
        return R.ok(Map.of(
            "status", "success",
            "source_version_number", result.sourceVersionNumber(),
            "version_number", result.restoredVersionNumber(),
            "version_id", result.restoredVersionId()
        ));
    }

    /**
     * 处理{@code test}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/test")
    public R<Map<String, Object>> test(@Valid @RequestBody PromptTestRequest request) {
        return R.ok(service.test(
            request.content(), request.variables(), request.userInput(), request.model()
        ));
    }

    /**
     * 处理{@code optimize}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping({"/optimize", "/optimize/agent-editor", "/optimize/task-instruction"})
    public R<Map<String, Object>> optimize(@Valid @RequestBody PromptContentRequest request) {
        return R.ok(service.optimize(request.content(), request.model()));
    }

    /**
     * 封装Save提示词相关的不可变数据。
     */
    public record SavePromptRequest(
        @NotBlank String source,
        @NotBlank String targetId,
        @NotBlank @Size(max = 100_000) String content,
        String versionNote
    ) {
        /**
         * 处理{@code targetId}并返回对应结果。
         *
         * @return 处理结果
         */
        public String targetId() {
            return targetId;
        }
    }

    /**
     * 封装提示词Content相关的不可变数据。
     */
    public record PromptContentRequest(
        @NotBlank @Size(max = 100_000) String content,
        String model
    ) {
    }

    /**
     * 封装Restore提示词相关的不可变数据。
     */
    public record RestorePromptRequest(
        @NotBlank String source,
        @NotBlank String targetId,
        @jakarta.validation.constraints.Min(1) int versionNumber
    ) {
    }

    /**
     * 封装提示词Test相关的不可变数据。
     */
    public record PromptTestRequest(
        @NotBlank @Size(max = 100_000) String content,
        Map<String, Object> variables,
        String userInput,
        String model
    ) {
    }
}
