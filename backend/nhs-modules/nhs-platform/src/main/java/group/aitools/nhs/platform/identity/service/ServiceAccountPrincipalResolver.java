package group.aitools.nhs.platform.identity.service;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.identity.domain.ServiceAccount;
import group.aitools.nhs.platform.identity.mapper.MachineIdentityMapper;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 负责Service账户操作主体相关的转换、解析或处理逻辑。
 * Resolves only live machine identities and never adapts a human IAM record. */
@Service
public class ServiceAccountPrincipalResolver {

    private final MachineIdentityMapper mapper;

    public ServiceAccountPrincipalResolver(MachineIdentityMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 校验{@code Active}，并在条件不满足时终止处理。
     *
     * @param serviceAccountId 资源标识
     * @return 处理结果
     */
    public CurrentPrincipal requireActive(Long serviceAccountId) {
        ServiceAccount account = mapper.selectServiceAccount(serviceAccountId);
        if (!active(account, LocalDateTime.now())) {
            throw new ServiceException("自动化服务账号无效或已失效", HttpStatus.FORBIDDEN);
        }
        return principal(account);
    }

    /**
 * 校验OwnedFor自动化，并在条件不满足时终止处理。
 * Resolves only an account owned by the frozen actor; administrators may select one explicitly. */
    public CurrentPrincipal requireOwnedForAutomation(
        CurrentPrincipal actor,
        Long requestedServiceAccountId
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal requiredActor = Objects.requireNonNull(actor, "actor must not be null");
        if (requiredActor.type() == PrincipalType.SERVICE_ACCOUNT) {
            if (requestedServiceAccountId != null
                && !requiredActor.id().equals(requestedServiceAccountId)) {
                throw new ServiceException("服务账号不能委托其他执行身份", HttpStatus.FORBIDDEN);
            }
            return requireActive(requiredActor.id());
        }

        LocalDateTime now = LocalDateTime.now();
        if (requestedServiceAccountId != null) {
            ServiceAccount selected = mapper.selectServiceAccount(requestedServiceAccountId);
            if (!active(selected, now)
                || (!requiredActor.id().equals(selected.getOwnerId())
                    && !requiredActor.hasRole(PlatformRole.PLATFORM_ADMIN))) {
                throw new ServiceException("无权使用该自动化服务账号", HttpStatus.FORBIDDEN);
            }
            return principal(selected);
        }

        List<ServiceAccount> owned = mapper.selectActiveAutomationAccountsByOwner(
            requiredActor.id(), now, 1
        );
        if (owned == null || owned.isEmpty()) {
            throw new ServiceException(
                "当前用户未配置可用的自动化服务账号", HttpStatus.BAD_REQUEST
            );
        }
        return principal(owned.getFirst());
    }

    /**
     * 处理{@code active}并返回对应结果。
     *
     * @param account 账户参数
     * @param now {@code now}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean active(ServiceAccount account, LocalDateTime now) {
        return account != null && "active".equals(account.getStatus())
            && (account.getExpiresAt() == null || account.getExpiresAt().isAfter(now));
    }

    /**
     * 处理操作主体并返回对应结果。
     *
     * @param account 账户参数
     * @return 处理结果
     */
    private CurrentPrincipal principal(ServiceAccount account) {
        String name = account.getName() == null || account.getName().isBlank()
            ? account.getAccountKey() : account.getName();
        return new CurrentPrincipal(
            account.getId(), name, PrincipalType.SERVICE_ACCOUNT,
            Set.of(PlatformRole.SERVICE_ACCOUNT)
        );
    }
}
