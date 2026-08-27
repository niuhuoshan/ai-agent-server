package group.aitools.nhs.platform.artifact.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.artifact.service.ArtifactApplicationService;
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
 * 提供平台制品相关的 HTTP 接口，并负责请求校验与结果返回。
 * Immutable task artifact registration and visibility-filtered queries. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/tasks/{taskId}")
public class PlatformArtifactController {

    private final ArtifactApplicationService artifactService;

    public PlatformArtifactController(ArtifactApplicationService artifactService) {
        this.artifactService = artifactService;
    }

    /**
     * 创建并保存{@code register}。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/runs/{runId}/artifacts")
    public R<ArtifactView> register(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long runId,
        @Valid @RequestBody RegisterArtifactRequest request
    ) {
        return R.ok(artifactService.register(taskId, runId, request));
    }

    /**
     * 查询{@code list}列表。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/artifacts")
    public R<List<ArtifactView>> list(
        @PathVariable @Positive Long taskId,
        @RequestParam(required = false) @Positive Long runId,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(artifactService.list(taskId, runId, limit));
    }

    /**
     * 获取{@code get}。
     *
     * @param taskId 资源标识
     * @param artifactId 资源标识
     * @return 处理结果
     */
    @GetMapping("/artifacts/{artifactId}")
    public R<ArtifactView> get(
        @PathVariable @Positive Long taskId,
        @PathVariable @Positive Long artifactId
    ) {
        return R.ok(artifactService.get(taskId, artifactId));
    }
}
