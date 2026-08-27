package group.aitools.nhs.platform.nhs.portal.slash;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
import java.util.Map;

/**
 * 提供门户Slash命令相关的 HTTP 接口，并负责请求校验与结果返回。
 * Nhs-compatible slash command endpoints. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/api/portal/slash-commands")
public class PortalSlashCommandController {

    private final PortalSlashCommandService service;

    public PortalSlashCommandController(PortalSlashCommandService service) {
        this.service = service;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping({"", "/"})
    public R<List<PortalSlashCommand>> list(
        @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit
    ) {
        return R.ok(service.list(limit));
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping({"", "/"})
    public R<PortalSlashCommand> create(@Valid @RequestBody CommandRequest request) {
        return R.ok(service.create(request.label(), request.command(), request.sortOrder()));
    }

    /**
     * 更新{@code update}。
     *
     * @param id 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{id}")
    public R<PortalSlashCommand> update(
        @PathVariable Long id,
        @Valid @RequestBody CommandRequest request
    ) {
        return R.ok(service.update(id, request.label(), request.command(), request.sortOrder()));
    }

    /**
     * 删除{@code delete}。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/{id}")
    public R<Map<String, String>> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok(Map.of("status", "success"));
    }

    /**
     * 处理{@code reorder}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/reorder")
    public R<Map<String, String>> reorder(@Valid @RequestBody ReorderRequest request) {
        service.reorder(request.items());
        return R.ok(Map.of("status", "success"));
    }

    /**
     * 封装命令相关的不可变数据。
     */
    public record CommandRequest(
        @NotBlank @Size(max = 128) String label,
        @NotBlank @Size(max = 2048) String command,
        int sortOrder
    ) {
    }

    /**
     * 封装{@code Reorder}相关的不可变数据。
     */
    public record ReorderRequest(List<PortalSlashCommandService.ReorderItem> items) {
    }
}
