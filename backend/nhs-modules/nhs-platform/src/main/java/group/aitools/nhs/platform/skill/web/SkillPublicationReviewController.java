package group.aitools.nhs.platform.skill.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.skill.service.SkillPublicationService;
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
 * 提供技能PublicationReview相关的 HTTP 接口，并负责请求校验与结果返回。
 * Reviewer queue for immutable personal Skill publication snapshots. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping({"/api/portal/skills/publication-requests", "/platform/skill-publications"})
public class SkillPublicationReviewController {

    private final SkillPublicationService service;

    public SkillPublicationReviewController(SkillPublicationService service) {
        this.service = service;
    }

    /**
     * 处理{@code pending}并返回对应结果。
     *
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<SkillPublicationView>> pending(
        @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return R.ok(service.pending(limit));
    }

    /**
     * 处理{@code detail}并返回对应结果。
     *
     * @param versionId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{versionId}")
    public R<SkillPublicationView> detail(@PathVariable @Positive Long versionId) {
        return R.ok(service.detail(versionId));
    }

    /**
     * 处理{@code approve}并返回对应结果。
     *
     * @param versionId 资源标识
     * @return 处理结果
     */
    @PostMapping("/{versionId}/approve")
    public R<SkillPublicationView> approve(@PathVariable @Positive Long versionId) {
        return R.ok(service.approve(versionId));
    }

    /**
     * 处理{@code reject}并返回对应结果。
     *
     * @param versionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{versionId}/reject")
    public R<SkillPublicationView> reject(
        @PathVariable @Positive Long versionId,
        @Valid @RequestBody RejectSkillPublicationRequest request
    ) {
        return R.ok(service.reject(versionId, request.comment()));
    }
}
