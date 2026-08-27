package group.aitools.nhs.platform.iam.management.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.iam.management.service.PermissionAdministrationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供平台权限Administration相关的 HTTP 接口，并负责请求校验与结果返回。
 * Permission profile, user capability and reference-copy management endpoints. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/iam")
public class PlatformPermissionAdministrationController {

    private final PermissionAdministrationService service;

    public PlatformPermissionAdministrationController(PermissionAdministrationService service) {
        this.service = service;
    }

    /**
     * 处理{@code profiles}并返回对应结果。
     *
     * @param status 目标状态
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/permission-profiles")
    public R<List<PermissionProfileView>> profiles(
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(service.profiles(status, limit));
    }

    /**
     * 处理配置档案并返回对应结果。
     *
     * @param profileId 资源标识
     * @return 处理结果
     */
    @GetMapping("/permission-profiles/{profileId}")
    public R<PermissionProfileView> profile(@PathVariable @Positive Long profileId) {
        return R.ok(service.profile(profileId));
    }

    /**
     * 创建并保存配置档案。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/permission-profiles")
    public R<PermissionProfileView> createProfile(
        @Valid @RequestBody CreatePermissionProfileRequest request
    ) {
        return R.ok(service.createProfile(request));
    }

    /**
     * 创建并保存配置档案版本。
     *
     * @param profileId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/permission-profiles/{profileId}/versions")
    public R<PermissionProfileView> createProfileVersion(
        @PathVariable @Positive Long profileId,
        @Valid @RequestBody CreatePermissionProfileVersionRequest request
    ) {
        return R.ok(service.createProfileVersion(profileId, request));
    }

    /**
     * 更新配置档案Status。
     *
     * @param profileId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PatchMapping("/permission-profiles/{profileId}/status")
    public R<PermissionProfileView> updateProfileStatus(
        @PathVariable @Positive Long profileId,
        @Valid @RequestBody UpdatePermissionProfileStatusRequest request
    ) {
        return R.ok(service.updateProfileStatus(profileId, request.status()));
    }

    /**
     * 处理{@code summary}并返回对应结果。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    @GetMapping("/users/{userId}/permission-summary")
    public R<PermissionSummaryView> summary(@PathVariable @Positive Long userId) {
        return R.ok(service.summary(userId));
    }

    /**
     * 处理{@code putBinding}并返回对应结果。
     *
     * @param userId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/users/{userId}/permission-binding")
    public R<PermissionBindingView> putBinding(
        @PathVariable @Positive Long userId,
        @Valid @RequestBody PutPermissionBindingRequest request
    ) {
        return R.ok(service.putBinding(userId, request));
    }

    /**
     * 处理{@code patchOverrides}并返回对应结果。
     *
     * @param userId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PatchMapping("/users/{userId}/permission-overrides")
    public R<PermissionSummaryView> patchOverrides(
        @PathVariable @Positive Long userId,
        @Valid @RequestBody PatchPermissionOverridesRequest request
    ) {
        return R.ok(service.patchOverrides(userId, request));
    }

    /**
     * 创建并保存{@code TemporaryGrant}。
     *
     * @param userId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/users/{userId}/temporary-grants")
    public R<PermissionRuleView> createTemporaryGrant(
        @PathVariable @Positive Long userId,
        @Valid @RequestBody CreateTemporaryGrantRequest request
    ) {
        return R.ok(service.createTemporaryGrant(userId, request));
    }

    /**
     * 处理{@code revokeTemporaryGrant}并返回对应结果。
     *
     * @param userId 资源标识
     * @param grantId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/users/{userId}/temporary-grants/{grantId}")
    public R<Void> revokeTemporaryGrant(
        @PathVariable @Positive Long userId,
        @PathVariable @Positive Long grantId
    ) {
        service.revokeTemporaryGrant(userId, grantId);
        return R.ok();
    }

    /**
     * 处理{@code diff}并返回对应结果。
     *
     * @param userId 资源标识
     * @param sourceUserId 资源标识
     * @return 处理结果
     */
    @GetMapping("/users/{userId}/permission-diff")
    public R<PermissionDiffView> diff(
        @PathVariable @Positive Long userId,
        @RequestParam @Positive Long sourceUserId
    ) {
        return R.ok(service.diff(userId, sourceUserId));
    }

    /**
     * 处理{@code copy}并返回对应结果。
     *
     * @param userId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/users/{userId}/permission-copy")
    public R<PermissionCopyResult> copy(
        @PathVariable @Positive Long userId,
        @Valid @RequestBody CopyPermissionRequest request
    ) {
        return R.ok(service.copy(userId, request));
    }

    /**
     * 处理{@code copyRecords}并返回对应结果。
     *
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/permission-copy-records")
    public R<List<PermissionCopyRecordView>> copyRecords(
        @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return R.ok(service.copyRecords(limit));
    }
}
