package group.aitools.nhs.platform.identity.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import group.aitools.nhs.platform.identity.service.IdentitySyncApplicationService;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.PreviewRequest;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.PreviewView;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.RunRequest;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.RunView;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.SsoSyncRequest;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供NhsSso用户SyncCompatibility相关的 HTTP 接口，并负责请求校验与结果返回。
 * Compatibility routes for the original Nhs SSO user-selection drawer. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/api/portal/management")
public class NhsSsoUserSyncCompatibilityController {

    private final IdentitySyncApplicationService service;

    public NhsSsoUserSyncCompatibilityController(IdentitySyncApplicationService service) {
        this.service = service;
    }

    /**
     * 处理{@code users}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/sso-users")
    public R<PreviewView> users() {
        return R.ok(service.preview(new PreviewRequest(null)));
    }

    /**
     * 处理{@code sync}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/sso-sync")
    public R<RunView> sync(@Valid @RequestBody SsoSyncRequest request) {
        return R.ok(service.execute(new RunRequest(request.usernames(), null)));
    }
}
