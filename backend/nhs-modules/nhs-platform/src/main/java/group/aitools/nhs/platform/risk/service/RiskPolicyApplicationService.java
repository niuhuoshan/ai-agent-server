package group.aitools.nhs.platform.risk.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.risk.domain.AgentRiskPolicy;
import group.aitools.nhs.platform.risk.mapper.RiskPolicyMapper;
import group.aitools.nhs.platform.risk.web.RiskPolicyView;
import group.aitools.nhs.platform.risk.web.SaveRiskPolicyRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 负责风险策略相关的业务编排与领域规则处理。
 * Administrator-managed structured risk-policy catalog. */
@Service
public class RiskPolicyApplicationService {

    private final CurrentPrincipalProvider principalProvider;
    private final PlatformIdGenerator idGenerator;
    private final RiskPolicyMapper mapper;

    public RiskPolicyApplicationService(
        CurrentPrincipalProvider principalProvider,
        PlatformIdGenerator idGenerator,
        RiskPolicyMapper mapper
    ) {
        this.principalProvider = principalProvider;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param resourceType 业务类型
     * @param riskLevel 风险Level参数
     * @param status 目标状态
     * @param search {@code search}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<RiskPolicyView> list(
        String resourceType,
        String riskLevel,
        String status,
        String search,
        int limit
    ) {
        requireAdministrator();
        return mapper.selectPolicies(
                normalize(resourceType), normalize(riskLevel), normalize(status), normalize(search), limit
            ).stream()
            .map(RiskPolicyView::from)
            .toList();
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public RiskPolicyView create(SaveRiskPolicyRequest request) {
        CurrentPrincipal principal = requireAdministrator();
        AgentRiskPolicy policy = new AgentRiskPolicy();
        policy.setId(idGenerator.nextId());
        apply(policy, request);
        policy.setCreateBy(principal.id());
        policy.setCreateTime(LocalDateTime.now());
        policy.setDelFlag("0");
        try {
            mapper.insertPolicy(policy);
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("风险策略标识已存在：" + policy.getPolicyKey(), HttpStatus.CONFLICT);
        }
        return RiskPolicyView.from(policy);
    }

    /**
     * 更新{@code update}。
     *
     * @param policyId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public RiskPolicyView update(Long policyId, SaveRiskPolicyRequest request) {
        CurrentPrincipal principal = requireAdministrator();
        AgentRiskPolicy policy = requirePolicy(policyId);
        apply(policy, request);
        policy.setUpdateBy(principal.id());
        policy.setUpdateTime(LocalDateTime.now());
        try {
            if (mapper.updatePolicy(policy) != 1) {
                throw notFound();
            }
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("风险策略标识已存在：" + policy.getPolicyKey(), HttpStatus.CONFLICT);
        }
        return RiskPolicyView.from(policy);
    }

    /**
     * 更新{@code Status}。
     *
     * @param policyId 资源标识
     * @param status 目标状态
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public RiskPolicyView updateStatus(Long policyId, String status) {
        CurrentPrincipal principal = requireAdministrator();
        if (!"active".equals(status) && !"disabled".equals(status)) {
            throw new ServiceException("风险策略状态无效", HttpStatus.BAD_REQUEST);
        }
        requirePolicy(policyId);
        if (mapper.updateStatus(policyId, status, principal.id(), LocalDateTime.now()) != 1) {
            throw notFound();
        }
        return RiskPolicyView.from(requirePolicy(policyId));
    }

    /**
     * 删除{@code delete}。
     *
     * @param policyId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long policyId) {
        CurrentPrincipal principal = requireAdministrator();
        requirePolicy(policyId);
        if (mapper.softDelete(policyId, principal.id(), LocalDateTime.now()) != 1) {
            throw notFound();
        }
    }

    /**
     * 处理{@code apply}相关逻辑。
     *
     * @param policy 策略参数
     * @param request 请求参数
     */
    private void apply(AgentRiskPolicy policy, SaveRiskPolicyRequest request) {
        String disposition = request.disposition().strip();
        String approvalRole = normalize(request.approvalRole());
        if ("approval_required".equals(disposition) && approvalRole == null) {
            throw new ServiceException("需要审批的策略必须选择审批角色", HttpStatus.BAD_REQUEST);
        }
        policy.setPolicyKey(request.policyKey().strip());
        policy.setName(request.name().strip());
        policy.setResourceType(request.resourceType().strip());
        policy.setAction(request.action().strip());
        policy.setRiskLevel(request.riskLevel());
        policy.setDisposition(disposition);
        policy.setApprovalRole("approval_required".equals(disposition) ? approvalRole : null);
        policy.setNotifyEnabled(request.notifyEnabled());
        policy.setPriority(request.priority());
        policy.setDescription(normalize(request.description()));
        policy.setStatus(request.status());
    }

    /**
     * 校验策略，并在条件不满足时终止处理。
     *
     * @param policyId 资源标识
     * @return 处理结果
     */
    private AgentRiskPolicy requirePolicy(Long policyId) {
        AgentRiskPolicy policy = mapper.selectPolicyById(policyId);
        if (policy == null) {
            throw notFound();
        }
        return policy;
    }

    /**
     * 校验{@code Administrator}，并在条件不满足时终止处理。
     *
     * @return 处理结果
     */
    private CurrentPrincipal requireAdministrator() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (!principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
            throw new ServiceException("仅平台管理员可以管理风险策略", HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 处理{@code notFound}并返回对应结果。
     *
     * @return 处理结果
     */
    private ServiceException notFound() {
        return new ServiceException("风险策略不存在", HttpStatus.NOT_FOUND);
    }
}
