package group.aitools.nhs.platform.execution.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * 获取{@code resolve}。
 *
 * 负责Frozen运行时操作主体相关的转换、解析或处理逻辑。
 * Rehydrates only the frozen runtime identity and strips human roles from service accounts. */
@Component
public class FrozenRuntimePrincipalResolver {

    public CurrentPrincipal resolve(AgentRunRequest request) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Map<String, Object> snapshot = request.authorizationSnapshot();
        Long principalId = positiveLong(snapshot.get("principalId"), "冻结主体 ID");
        if (!request.userId().equals(principalId)) {
            throw new SecurityException("运行主体与授权快照不一致");
        }
        String type = requiredText(snapshot.get("principalType"), "冻结主体类型");
        PrincipalType principalType = switch (type) {
            case "human" -> PrincipalType.HUMAN;
            case "service_account" -> PrincipalType.SERVICE_ACCOUNT;
            default -> throw new SecurityException("冻结主体类型无效");
        };
        EnumSet<PlatformRole> roles = EnumSet.noneOf(PlatformRole.class);
        if (snapshot.get("roles") instanceof List<?> rawRoles) {
            for (Object rawRole : rawRoles) {
                if (rawRole instanceof String key) {
                    PlatformRole.fromKey(key).ifPresent(roles::add);
                }
            }
        }
        if (principalType == PrincipalType.SERVICE_ACCOUNT) {
            roles.clear();
            roles.add(PlatformRole.SERVICE_ACCOUNT);
        } else {
            roles.remove(PlatformRole.SERVICE_ACCOUNT);
        }
        if (roles.isEmpty()) {
            throw new SecurityException("冻结主体没有有效平台角色");
        }
        return new CurrentPrincipal(
            principalId, "runtime-" + type + "-" + principalId, principalType, roles
        );
    }

    /**
     * 处理{@code positiveLong}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Long positiveLong(Object value, String label) {
        if (!(value instanceof Number number) || number.longValue() <= 0
            || number.doubleValue() != number.longValue()) {
            throw new SecurityException(label + "无效");
        }
        return number.longValue();
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String requiredText(Object value, String label) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new SecurityException(label + "无效");
        }
        return text.strip();
    }
}
