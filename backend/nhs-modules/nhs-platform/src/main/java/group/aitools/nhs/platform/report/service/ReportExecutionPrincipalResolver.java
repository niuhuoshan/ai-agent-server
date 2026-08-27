package group.aitools.nhs.platform.report.service;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.report.mapper.AgentReportMapper;
import group.aitools.nhs.platform.report.persistence.row.ReportExecutionPrincipalRow;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 负责报表执行操作主体相关的转换、解析或处理逻辑。
 * Resolves a current human identity for authorization outside the active login principal. */
@Component
public class ReportExecutionPrincipalResolver {

    private final AgentReportMapper mapper;

    public ReportExecutionPrincipalResolver(AgentReportMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 获取{@code resolve}。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    public CurrentPrincipal resolve(Long userId) {
        ReportExecutionPrincipalRow user = mapper.selectReportExecutionPrincipal(userId);
        return resolve(user, "报表订阅创建人已停用或不存在", HttpStatus.FORBIDDEN);
    }

    /**
     * 获取{@code resolve}。
     *
     * @param username 名称
     * @return 处理结果
     */
    public CurrentPrincipal resolve(String username) {
        if (username == null || username.isBlank()) {
            throw new ServiceException("用户名不能为空", HttpStatus.BAD_REQUEST);
        }
        ReportExecutionPrincipalRow user = mapper.selectReportExecutionPrincipalByUsername(username.strip());
        return resolve(user, "用户不存在或已禁用", HttpStatus.NOT_FOUND);
    }

    /**
     * 获取{@code resolve}。
     *
     * @param user 用户参数
     * @param unavailableMessage 待处理内容
     * @param unavailableCode {@code unavailableCode}参数
     * @return 处理结果
     */
    private CurrentPrincipal resolve(ReportExecutionPrincipalRow user, String unavailableMessage, int unavailableCode) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (user == null || !"0".equals(user.getStatus()) || !"0".equals(user.getDelFlag())) {
            throw unavailable(unavailableMessage, unavailableCode);
        }
        if ("service_account".equalsIgnoreCase(user.getUserType())) {
            throw unavailable("不能使用服务账号执行用户级数据查询", HttpStatus.FORBIDDEN);
        }
        Long userId = user.getUserId();
        if (userId == null || userId <= 0) {
            throw unavailable(unavailableMessage, unavailableCode);
        }
        Set<PlatformRole> roles = new LinkedHashSet<>();
        roles.add(PlatformRole.MEMBER);
        for (String roleKey : mapper.selectReportExecutionRoleKeys(userId)) {
            PlatformRole.fromKey(roleKey).ifPresent(roles::add);
            if ("superadmin".equals(normalize(roleKey))) {
                roles.add(PlatformRole.PLATFORM_ADMIN);
            }
        }
        roles.remove(PlatformRole.SERVICE_ACCOUNT);
        String username = user.getUserName();
        if (username == null || username.isBlank()) {
            username = "user-" + userId;
        }
        return new CurrentPrincipal(userId, username, PrincipalType.HUMAN, roles);
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 处理{@code unavailable}并返回对应结果。
     *
     * @param message 待处理内容
     * @param code {@code code}参数
     * @return 处理结果
     */
    private ServiceException unavailable(String message, int code) {
        return new ServiceException(message, code);
    }
}
