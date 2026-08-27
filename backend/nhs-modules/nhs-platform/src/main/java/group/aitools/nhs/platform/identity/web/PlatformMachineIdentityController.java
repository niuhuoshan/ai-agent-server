package group.aitools.nhs.platform.identity.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.identity.service.MachineIdentityApplicationService;
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
 * 提供平台Machine身份相关的 HTTP 接口，并负责请求校验与结果返回。
 * Administrative machine-identity and one-time API credential endpoints. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/iam")
public class PlatformMachineIdentityController {

    private final MachineIdentityApplicationService service;

    public PlatformMachineIdentityController(MachineIdentityApplicationService service) {
        this.service = service;
    }

    /**
     * 处理{@code serviceAccounts}并返回对应结果。
     *
     * @param status 目标状态
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/service-accounts")
    public R<List<ServiceAccountView>> serviceAccounts(
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(service.serviceAccounts(status, limit));
    }

    /**
     * 处理service账户并返回对应结果。
     *
     * @param accountId 资源标识
     * @return 处理结果
     */
    @GetMapping("/service-accounts/{accountId}")
    public R<ServiceAccountView> serviceAccount(@PathVariable @Positive Long accountId) {
        return R.ok(service.serviceAccount(accountId));
    }

    /**
     * 处理service账户Grants并返回对应结果。
     *
     * @param accountId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/service-accounts/{accountId}/grants")
    public R<List<ServiceAccountGrantView>> serviceAccountGrants(
        @PathVariable @Positive Long accountId,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(service.serviceAccountGrants(accountId, limit));
    }

    /**
     * 创建并保存Service账户Grant。
     *
     * @param accountId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/service-accounts/{accountId}/grants")
    public R<ServiceAccountGrantView> createServiceAccountGrant(
        @PathVariable @Positive Long accountId,
        @Valid @RequestBody CreateServiceAccountGrantRequest request
    ) {
        return R.ok(service.createServiceAccountGrant(accountId, request));
    }

    /**
     * 处理revokeService账户Grant并返回对应结果。
     *
     * @param accountId 资源标识
     * @param grantId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/service-accounts/{accountId}/grants/{grantId}")
    public R<Void> revokeServiceAccountGrant(
        @PathVariable @Positive Long accountId,
        @PathVariable @Positive Long grantId
    ) {
        service.revokeServiceAccountGrant(accountId, grantId);
        return R.ok();
    }

    /**
     * 创建并保存Service账户。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/service-accounts")
    public R<ServiceAccountView> createServiceAccount(
        @Valid @RequestBody CreateServiceAccountRequest request
    ) {
        return R.ok(service.createServiceAccount(request));
    }

    /**
     * 更新Service账户。
     *
     * @param accountId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/service-accounts/{accountId}")
    public R<ServiceAccountView> updateServiceAccount(
        @PathVariable @Positive Long accountId,
        @Valid @RequestBody UpdateServiceAccountRequest request
    ) {
        return R.ok(service.updateServiceAccount(accountId, request));
    }

    /**
     * 更新Service账户Status。
     *
     * @param accountId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PatchMapping("/service-accounts/{accountId}/status")
    public R<ServiceAccountView> updateServiceAccountStatus(
        @PathVariable @Positive Long accountId,
        @Valid @RequestBody UpdateMachineIdentityStatusRequest request
    ) {
        return R.ok(service.updateServiceAccountStatus(accountId, request.status()));
    }

    /**
     * 处理接口Applications并返回对应结果。
     *
     * @param status 目标状态
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/api-applications")
    public R<List<ApiApplicationView>> apiApplications(
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(service.apiApplications(status, limit));
    }

    /**
     * 处理接口应用并返回对应结果。
     *
     * @param applicationId 资源标识
     * @return 处理结果
     */
    @GetMapping("/api-applications/{applicationId}")
    public R<ApiApplicationView> apiApplication(@PathVariable @Positive Long applicationId) {
        return R.ok(service.apiApplication(applicationId));
    }

    /**
     * 创建并保存接口应用。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/api-applications")
    public R<ApiApplicationView> createApiApplication(
        @Valid @RequestBody CreateApiApplicationRequest request
    ) {
        return R.ok(service.createApiApplication(request));
    }

    /**
     * 更新接口应用。
     *
     * @param applicationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/api-applications/{applicationId}")
    public R<ApiApplicationView> updateApiApplication(
        @PathVariable @Positive Long applicationId,
        @Valid @RequestBody UpdateApiApplicationRequest request
    ) {
        return R.ok(service.updateApiApplication(applicationId, request));
    }

    /**
     * 更新接口应用Status。
     *
     * @param applicationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PatchMapping("/api-applications/{applicationId}/status")
    public R<ApiApplicationView> updateApiApplicationStatus(
        @PathVariable @Positive Long applicationId,
        @Valid @RequestBody UpdateMachineIdentityStatusRequest request
    ) {
        return R.ok(service.updateApiApplicationStatus(applicationId, request.status()));
    }

    /**
     * 判断sue凭据是否满足要求。
     *
     * @param applicationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/api-applications/{applicationId}/credentials")
    public R<IssuedApiCredentialView> issueCredential(
        @PathVariable @Positive Long applicationId,
        @Valid @RequestBody IssueApiCredentialRequest request
    ) {
        return R.ok(service.issueCredential(applicationId, request));
    }

    /**
     * 处理{@code credentials}并返回对应结果。
     *
     * @param applicationId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/api-applications/{applicationId}/credentials")
    public R<List<ApiCredentialView>> credentials(
        @PathVariable @Positive Long applicationId,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
    ) {
        return R.ok(service.credentials(applicationId, limit));
    }

    /**
     * 处理revoke凭据并返回对应结果。
     *
     * @param applicationId 资源标识
     * @param credentialId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/api-applications/{applicationId}/credentials/{credentialId}")
    public R<Void> revokeCredential(
        @PathVariable @Positive Long applicationId,
        @PathVariable @Positive Long credentialId
    ) {
        service.revokeCredential(applicationId, credentialId);
        return R.ok();
    }
}
