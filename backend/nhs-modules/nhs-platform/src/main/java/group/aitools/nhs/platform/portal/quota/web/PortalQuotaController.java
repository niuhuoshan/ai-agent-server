package group.aitools.nhs.platform.portal.quota.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.portal.quota.service.PortalQuotaService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提供门户Quota相关的 HTTP 接口，并负责请求校验与结果返回。
 * Nhs portal monthly Token quota compatibility endpoints. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/api/portal/quota")
public class PortalQuotaController {

    private final PortalQuotaService service;

    public PortalQuotaController(PortalQuotaService service) {
        this.service = service;
    }

    /**
     * 处理{@code me}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/me")
    public R<Map<String, Object>> me() {
        return R.ok(service.myQuota());
    }

    /**
     * 处理系统并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/system")
    public R<Map<String, Object>> system() {
        return R.ok(service.policy("system", null));
    }

    /**
     * 更新系统。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/system")
    public R<Map<String, Object>> updateSystem(@Valid @RequestBody QuotaPolicyRequest request) {
        return R.ok(service.upsert("system", null, request));
    }

    /**
     * 删除系统。
     *
     * @return 处理结果
     */
    @DeleteMapping("/system")
    public R<Map<String, Object>> deleteSystem() {
        service.delete("system", null);
        return R.ok(Map.of("message", "已恢复为无系统默认额度"));
    }

    /**
     * 处理用户并返回对应结果。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    @GetMapping("/users/{userId}")
    public R<Map<String, Object>> user(@PathVariable @Positive Long userId) {
        return R.ok(service.policy("user", userId));
    }

    /**
     * 更新用户。
     *
     * @param userId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/users/{userId}")
    public R<Map<String, Object>> updateUser(
        @PathVariable @Positive Long userId,
        @Valid @RequestBody QuotaPolicyRequest request
    ) {
        return R.ok(service.upsert("user", userId, request));
    }

    /**
     * 删除用户。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/users/{userId}")
    public R<Map<String, Object>> deleteUser(@PathVariable @Positive Long userId) {
        service.delete("user", userId);
        return R.ok(Map.of("message", "已清除用户专属额度，将继承角色或系统策略"));
    }

    /**
     * 处理角色并返回对应结果。
     *
     * @param roleId 资源标识
     * @return 处理结果
     */
    @GetMapping("/roles/{roleId}")
    public R<Map<String, Object>> role(@PathVariable @Positive Long roleId) {
        return R.ok(service.policy("role", roleId));
    }

    /**
     * 更新角色。
     *
     * @param roleId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/roles/{roleId}")
    public R<Map<String, Object>> updateRole(
        @PathVariable @Positive Long roleId,
        @Valid @RequestBody QuotaPolicyRequest request
    ) {
        return R.ok(service.upsert("role", roleId, request));
    }

    /**
     * 删除角色。
     *
     * @param roleId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/roles/{roleId}")
    public R<Map<String, Object>> deleteRole(@PathVariable @Positive Long roleId) {
        service.delete("role", roleId);
        return R.ok(Map.of("message", "已清除角色额度模板"));
    }
}
