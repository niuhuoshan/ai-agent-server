package group.aitools.nhs.platform.canvas.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.canvas.service.ConversationCanvasService;
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
 * 提供平台画布相关的 HTTP 接口，并负责请求校验与结果返回。
 * Owner-only Canvas API nested under a private conversation. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/conversations/{conversationId}/canvases")
public class PlatformCanvasController {

    private final ConversationCanvasService service;

    public PlatformCanvasController(ConversationCanvasService service) {
        this.service = service;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param conversationId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<CanvasView>> list(
        @PathVariable @Positive Long conversationId,
        @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit
    ) {
        return R.ok(service.list(conversationId, limit));
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param conversationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping
    public R<CanvasView> create(
        @PathVariable @Positive Long conversationId,
        @Valid @RequestBody CreateCanvasRequest request
    ) {
        return R.ok(service.create(conversationId, request));
    }

    /**
     * 获取{@code get}。
     *
     * @param conversationId 资源标识
     * @param canvasId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{canvasId}")
    public R<CanvasView> get(
        @PathVariable @Positive Long conversationId,
        @PathVariable @Positive Long canvasId
    ) {
        return R.ok(service.get(conversationId, canvasId));
    }

    /**
     * 更新{@code update}。
     *
     * @param conversationId 资源标识
     * @param canvasId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{canvasId}")
    public R<CanvasView> update(
        @PathVariable @Positive Long conversationId,
        @PathVariable @Positive Long canvasId,
        @Valid @RequestBody UpdateCanvasRequest request
    ) {
        return R.ok(service.update(conversationId, canvasId, request));
    }

    /**
     * 删除{@code delete}。
     *
     * @param conversationId 资源标识
     * @param canvasId 资源标识
     * @param expectedVersion expected版本参数
     * @return 处理结果
     */
    @DeleteMapping("/{canvasId}")
    public R<Void> delete(
        @PathVariable @Positive Long conversationId,
        @PathVariable @Positive Long canvasId,
        @RequestParam @Min(1) int expectedVersion
    ) {
        service.delete(conversationId, canvasId, expectedVersion);
        return R.ok();
    }

    /**
     * 处理{@code versions}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param canvasId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/{canvasId}/versions")
    public R<List<CanvasVersionView>> versions(
        @PathVariable @Positive Long conversationId,
        @PathVariable @Positive Long canvasId,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(service.versions(conversationId, canvasId, limit));
    }

    /**
     * 处理{@code restore}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param canvasId 资源标识
     * @param versionNo 版本No参数
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{canvasId}/versions/{versionNo}/restore")
    public R<CanvasView> restore(
        @PathVariable @Positive Long conversationId,
        @PathVariable @Positive Long canvasId,
        @PathVariable @Positive int versionNo,
        @Valid @RequestBody RestoreCanvasVersionRequest request
    ) {
        return R.ok(service.restore(conversationId, canvasId, versionNo, request));
    }

    /**
     * 保存To工作空间。
     *
     * @param conversationId 资源标识
     * @param canvasId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{canvasId}/save-to-workspace")
    public R<CanvasWorkspaceSaveView> saveToWorkspace(
        @PathVariable @Positive Long conversationId,
        @PathVariable @Positive Long canvasId,
        @Valid @RequestBody SaveCanvasToWorkspaceRequest request
    ) {
        return R.ok(service.saveToWorkspace(conversationId, canvasId, request));
    }
}
