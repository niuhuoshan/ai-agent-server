package group.aitools.nhs.platform.model.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import group.aitools.nhs.platform.model.service.ModelApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供平台模型相关的 HTTP 接口，并负责请求校验与结果返回。
 * Model registry, discovery and connectivity endpoints. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping({"/platform/models", "/api/portal/models"})
public class PlatformModelController {

    private final ModelApplicationService modelService;

    public PlatformModelController(ModelApplicationService modelService) {
        this.modelService = modelService;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param modelType 业务类型
     * @param providerType 业务类型
     * @param search {@code search}参数
     * @param includeInactive {@code includeInactive}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<ModelView>> list(
        @RequestParam(required = false)
        @Pattern(regexp = "chat|embedding|multimodal|rerank") String modelType,
        @RequestParam(required = false)
        @Pattern(regexp = "openai|openai-compatible") String providerType,
        @RequestParam(required = false) @Size(max = 128) String search,
        @RequestParam(defaultValue = "false") boolean includeInactive,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(modelService.list(modelType, providerType, search, includeInactive, limit));
    }

    /**
     * 获取{@code get}。
     *
     * @param modelId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{modelId}")
    public R<ModelView> get(@PathVariable @Positive Long modelId) {
        return R.ok(modelService.get(modelId));
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping
    public R<ModelView> create(@Valid @RequestBody CreateModelRequest request) {
        return R.ok(modelService.create(request));
    }

    /**
     * 更新{@code update}。
     *
     * @param modelId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{modelId}")
    public R<ModelView> update(
        @PathVariable @Positive Long modelId,
        @Valid @RequestBody UpdateModelRequest request
    ) {
        return R.ok(modelService.update(modelId, request));
    }

    /**
     * 处理{@code references}并返回对应结果。
     *
     * @param modelId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{modelId}/references")
    public R<List<ModelReferenceView>> references(@PathVariable @Positive Long modelId) {
        return R.ok(modelService.references(modelId));
    }

    /**
     * 删除{@code delete}。
     *
     * @param modelId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/{modelId}")
    public R<Void> delete(@PathVariable @Positive Long modelId) {
        modelService.delete(modelId);
        return R.ok();
    }

    /**
     * 处理{@code test}并返回对应结果。
     *
     * @param modelId 资源标识
     * @return 处理结果
     */
    @PostMapping("/{modelId}/test")
    public R<ModelConnectionView> test(@PathVariable @Positive Long modelId) {
        return R.ok(modelService.test(modelId));
    }

    /**
     * 处理{@code testConfig}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/test-config")
    public R<ModelConnectionView> testConfig(@Valid @RequestBody TestModelConfigRequest request) {
        return R.ok(modelService.testConfig(request));
    }

    /**
     * 处理{@code discover}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/discover")
    public R<List<ModelOptionView>> discover(@Valid @RequestBody DiscoverModelsRequest request) {
        return R.ok(modelService.discover(request));
    }
}
