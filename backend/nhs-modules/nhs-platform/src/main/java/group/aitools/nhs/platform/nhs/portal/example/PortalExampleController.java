package group.aitools.nhs.platform.nhs.portal.example;

import com.fasterxml.jackson.annotation.JsonProperty;
import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
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

import java.util.Map;
import java.util.List;

/**
 * 提供门户Example相关的 HTTP 接口，并负责请求校验与结果返回。
 * Nhs-compatible local ChatBI/Few-shot example management endpoints. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/api/portal/examples")
public class PortalExampleController {

    private final PortalExampleService service;

    public PortalExampleController(PortalExampleService service) {
        this.service = service;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param id 资源标识
     * @param agentId 资源标识
     * @param datasetId 资源标识
     * @param status 目标状态
     * @param category {@code category}参数
     * @param search {@code search}参数
     * @param page {@code page}参数
     * @param size 数量上限
     * @return 处理结果
     */
    @GetMapping({"", "/"})
    public R<PortalExampleService.PageResult> list(
        @RequestParam(required = false) Long id,
        @RequestParam(name = "agent_id", required = false) String agentId,
        @RequestParam(name = "dataset_id", required = false) Long datasetId,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size
    ) {
        PortalExampleService.PageResult result = service.list(new PortalExampleService.ListRequest(
            id, agentId, datasetId, status, category, search, page, size
        ));
        return R.ok(result);
    }

    /**
     * 获取{@code get}。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @GetMapping("/{id}")
    public R<Map<String, Object>> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    /**
     * 处理历史记录并返回对应结果。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @GetMapping("/{id}/history")
    public R<List<Map<String, Object>>> history(@PathVariable Long id) {
        return R.ok(service.history(id));
    }

    /**
     * 更新{@code update}。
     *
     * @param id 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{id}")
    public R<Map<String, Object>> update(
        @PathVariable Long id,
        @Valid @RequestBody UpdateExampleRequest request
    ) {
        return R.ok(service.update(id, request.toServiceRequest()));
    }

    /**
     * 处理审计并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/audit")
    public R<Map<String, Object>> audit(@Valid @RequestBody AuditRequest request) {
        return R.ok(service.audit(request.id(), request.status()));
    }

    /**
     * 处理{@code enhance}并返回对应结果。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @PostMapping("/{id}/enhance")
    public R<Map<String, Object>> enhance(@PathVariable Long id) {
        return R.ok(service.enhance(id));
    }

    /**
     * 处理{@code syncAll}并返回对应结果。
     *
     * @return 处理结果
     */
    @PostMapping("/sync-all")
    public R<Map<String, Object>> syncAll() {
        return R.ok(service.syncAll());
    }

    /**
     * 处理{@code sync}并返回对应结果。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @PostMapping("/sync/{id}")
    public R<Map<String, Object>> sync(@PathVariable Long id) {
        return R.ok(service.sync(id));
    }

    /**
     * 删除{@code delete}。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }

    /**
     * 封装审计相关的不可变数据。
     */
    public record AuditRequest(
        @JsonProperty("id") Long id,
        @NotBlank @Size(max = 32) String status
    ) {
    }

    /**
     * 封装{@code UpdateExample}相关的不可变数据。
     */
    public record UpdateExampleRequest(
        @JsonProperty("user_query") @Size(max = 200_000) String userQuery,
        @JsonProperty("refined_query") @Size(max = 200_000) String refinedQuery,
        @JsonProperty("context_summary") @Size(max = 200_000) String contextSummary,
        @JsonProperty("sql_text") @Size(max = 65_536) String sqlText,
        @JsonProperty("sql_metadata") Map<String, Object> sqlMetadata,
        @Size(max = 32) String category
    ) {
        /**
         * 将输入数据转换为{@code ServiceRequest}。
         *
         * @return 处理结果
         */
        PortalExampleService.UpdateRequest toServiceRequest() {
            return new PortalExampleService.UpdateRequest(
                userQuery, refinedQuery, contextSummary, sqlText, sqlMetadata, category
            );
        }
    }
}
