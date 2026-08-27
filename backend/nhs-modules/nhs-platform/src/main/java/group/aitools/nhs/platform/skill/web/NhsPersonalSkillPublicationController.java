package group.aitools.nhs.platform.skill.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.skill.service.SkillPublicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供NhsPersonal技能Publication相关的 HTTP 接口，并负责请求校验与结果返回。
 * Nhs-compatible personal Skill publication routes. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping({"/api/portal/personal-skills", "/api/portal/skills/personal"})
public class NhsPersonalSkillPublicationController {

    private final SkillPublicationService service;

    public NhsPersonalSkillPublicationController(SkillPublicationService service) {
        this.service = service;
    }

    /**
     * 处理{@code submit}并返回对应结果。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    @PostMapping("/{skillId}/publication-requests")
    public R<SkillPublicationView> submit(@PathVariable @Positive Long skillId) {
        return R.ok(service.submit(skillId));
    }

    /**
     * 处理{@code withdraw}并返回对应结果。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    @PostMapping("/{skillId}/publication-requests/withdraw")
    public R<SkillPublicationView> withdraw(@PathVariable @Positive Long skillId) {
        return R.ok(service.withdraw(skillId));
    }

    /**
     * 处理{@code status}并返回对应结果。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{skillId}/publication-status")
    public R<SkillPublicationView> status(@PathVariable @Positive Long skillId) {
        return R.ok(service.status(skillId));
    }
}
