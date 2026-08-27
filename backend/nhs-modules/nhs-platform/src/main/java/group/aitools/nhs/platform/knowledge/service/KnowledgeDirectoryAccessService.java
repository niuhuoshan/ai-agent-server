package group.aitools.nhs.platform.knowledge.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeBase;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeDirectory;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeDirectoryAcl;
import group.aitools.nhs.platform.knowledge.mapper.KnowledgeCatalogMapper;
import group.aitools.nhs.platform.knowledge.mapper.KnowledgeDirectoryAclMapper;
import group.aitools.nhs.platform.knowledge.web.KnowledgeDirectoryAclView;
import group.aitools.nhs.platform.knowledge.web.PutKnowledgeDirectoryAclRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 负责知识库目录Access相关的业务编排与领域规则处理。
 *
 * Evaluates directory ACLs for both HTTP catalog operations and runtime retrieval.
 * An ACL on an ancestor applies when inheritChildren is true; any applicable deny wins.
 */
@Service
public class KnowledgeDirectoryAccessService {

    private final KnowledgeCatalogMapper catalogMapper;
    private final KnowledgeDirectoryAclMapper aclMapper;
    private final PlatformIdGenerator idGenerator;
    private final KnowledgeOperationAuditService audit;

    public KnowledgeDirectoryAccessService(
        KnowledgeCatalogMapper catalogMapper,
        KnowledgeDirectoryAclMapper aclMapper,
        PlatformIdGenerator idGenerator,
        KnowledgeOperationAuditService audit
    ) {
        this.catalogMapper = catalogMapper;
        this.aclMapper = aclMapper;
        this.idGenerator = idGenerator;
        this.audit = audit;
    }

    /**
     * 处理{@code access}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param baseId 资源标识
     * @param permission 权限参数
     * @return 处理结果
     */
    public DirectoryAccess access(CurrentPrincipal principal, Long baseId, String permission) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (principal == null || baseId == null) {
            return DirectoryAccess.denyAll();
        }
        AgentKnowledgeBase base = catalogMapper.selectBaseById(baseId);
        if (base == null || isOwnerOrAdmin(principal, base)) {
            return DirectoryAccess.all();
        }
        List<AgentKnowledgeDirectory> directories = catalogMapper.selectDirectories(baseId);
        List<AgentKnowledgeDirectoryAcl> rules = aclMapper.selectActiveForUser(baseId, principal.id());
        if (rules.isEmpty()) {
            return DirectoryAccess.all();
        }
        Map<Long, AgentKnowledgeDirectory> byId = new HashMap<>();
        directories.forEach(directory -> byId.put(directory.getId(), directory));
        Set<Long> allowed = new HashSet<>();
        for (AgentKnowledgeDirectory directory : directories) {
            if (allowed(directory.getId(), byId, rules, permission)) {
                allowed.add(directory.getId());
            }
        }
        return new DirectoryAccess(allowed, allowedRoot(rules, permission), false);
    }

    /**
     * 校验{@code require}，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param baseId 资源标识
     * @param directoryId 资源标识
     * @param permission 权限参数
     */
    public void require(
        CurrentPrincipal principal, Long baseId, Long directoryId, String permission
    ) {
        DirectoryAccess result = access(principal, baseId, permission);
        if (!result.allows(directoryId)) {
            throw new ServiceException(
                "知识目录当前用户无" + permission + "权限", HttpStatus.FORBIDDEN
            );
        }
    }

    /**
     * 处理allowed目录Ids并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param baseId 资源标识
     * @param permission 权限参数
     * @return 符合条件的数据集合
     */
    public List<Long> allowedDirectoryIds(CurrentPrincipal principal, Long baseId, String permission) {
        return new ArrayList<>(access(principal, baseId, permission).directoryIds());
    }

    /**
     * 处理{@code allowsRoot}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param baseId 资源标识
     * @param permission 权限参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean allowsRoot(CurrentPrincipal principal, Long baseId, String permission) {
        return access(principal, baseId, permission).rootAllowed();
    }

    /**
     * 查询{@code list}列表。
     *
     * @param baseId 资源标识
     * @param directoryId 资源标识
     * @param root {@code root}参数
     * @param actor {@code actor}参数
     * @return 符合条件的数据集合
     */
    @Transactional(rollbackFor = Exception.class)
    public List<KnowledgeDirectoryAclView> list(
        Long baseId, Long directoryId, boolean root, CurrentPrincipal actor
    ) {
        requireManager(baseId, actor);
        return aclMapper.selectActiveForBase(baseId).stream()
            .filter(acl -> root
                ? acl.getDirectoryId() == null
                : directoryId == null || directoryId.equals(acl.getDirectoryId()))
            .map(KnowledgeDirectoryAclView::from)
            .toList();
    }

    /**
     * 处理{@code put}并返回对应结果。
     *
     * @param baseId 资源标识
     * @param request 请求参数
     * @param actor {@code actor}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDirectoryAclView put(
        Long baseId, PutKnowledgeDirectoryAclRequest request, CurrentPrincipal actor
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        requireManager(baseId, actor);
        if (request == null || request.userId() == null || request.userId() <= 0
            || actor.id().equals(request.userId())) {
            throw badRequest("目录 ACL 用户无效，不能给自己写入目录规则");
        }
        String permission = enumValue(request.permission(), Set.of("read", "write"), "目录 ACL 动作");
        String effect = enumValue(request.effect(), Set.of("allow", "deny"), "目录 ACL 效果");
        if (request.directoryId() != null) {
            requireDirectory(baseId, request.directoryId());
        }
        AgentKnowledgeDirectoryAcl existing = aclMapper.selectActiveTarget(
            baseId, request.directoryId(), request.userId(), permission
        );
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            AgentKnowledgeDirectoryAcl created = new AgentKnowledgeDirectoryAcl();
            created.setId(idGenerator.nextId());
            created.setKnowledgeBaseId(baseId);
            created.setDirectoryId(request.directoryId());
            created.setUserId(request.userId());
            created.setPermission(permission);
            created.setEffect(effect);
            created.setInheritChildren(request.inheritChildren());
            created.setRevisionNo(1L);
            created.setCreatedBy(actor.id());
            created.setCreatedAt(now);
            try {
                aclMapper.insert(created);
            } catch (DuplicateKeyException exception) {
                throw conflict("目录 ACL 已被其他请求创建");
            }
            audit.record(actor, "knowledge_directory_acl_put", "knowledge_directory_acl", created.getId(),
                summary(created));
            return KnowledgeDirectoryAclView.from(created);
        }
        if (request.expectedRevision() == null
            || !request.expectedRevision().equals(existing.getRevisionNo())) {
            throw conflict("目录 ACL 已被其他请求修改");
        }
        existing.setEffect(effect);
        existing.setInheritChildren(request.inheritChildren());
        existing.setUpdatedBy(actor.id());
        existing.setUpdatedAt(now);
        if (aclMapper.update(existing) != 1) {
            throw conflict("目录 ACL 已被其他请求修改");
        }
        audit.record(actor, "knowledge_directory_acl_put", "knowledge_directory_acl", existing.getId(),
            summary(existing));
        existing.setRevisionNo(existing.getRevisionNo() + 1);
        return KnowledgeDirectoryAclView.from(existing);
    }

    /**
     * 处理{@code revoke}相关逻辑。
     *
     * @param baseId 资源标识
     * @param aclId 资源标识
     * @param expectedRevision {@code expectedRevision}参数
     * @param actor {@code actor}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long baseId, Long aclId, Long expectedRevision, CurrentPrincipal actor) {
        requireManager(baseId, actor);
        AgentKnowledgeDirectoryAcl current = aclMapper.selectById(baseId, aclId);
        if (current == null || !"active".equals(current.getStatus())) {
            throw new ServiceException("目录 ACL 不存在", HttpStatus.NOT_FOUND);
        }
        if (expectedRevision == null || !expectedRevision.equals(current.getRevisionNo())) {
            throw conflict("目录 ACL 已被其他请求修改");
        }
        if (aclMapper.revoke(baseId, aclId, expectedRevision, actor.id(), LocalDateTime.now()) != 1) {
            throw conflict("目录 ACL 已被其他请求修改");
        }
        audit.record(actor, "knowledge_directory_acl_revoke", "knowledge_directory_acl", aclId,
            "baseId=" + baseId + ";userId=" + current.getUserId());
    }

    /**
     * 处理{@code allowed}并返回对应结果。
     *
     * @param directoryId 资源标识
     * @param byId 资源标识
     * @param rules {@code rules}参数
     * @param permission 权限参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean allowed(
        Long directoryId,
        Map<Long, AgentKnowledgeDirectory> byId,
        List<AgentKnowledgeDirectoryAcl> rules,
        String permission
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Set<Long> ancestors = new HashSet<>();
        Long current = directoryId;
        while (current != null && ancestors.add(current)) {
            AgentKnowledgeDirectory directory = byId.get(current);
            if (directory == null) {
                break;
            }
            current = directory.getParentId();
        }
        boolean allow = false;
        boolean deny = false;
        for (AgentKnowledgeDirectoryAcl rule : rules) {
            if (!permission.equals(rule.getPermission())) {
                continue;
            }
            Long ruleDirectory = rule.getDirectoryId();
            boolean applicable = ruleDirectory == null
                ? Boolean.TRUE.equals(rule.getInheritChildren())
                : ruleDirectory.equals(directoryId)
                    || (Boolean.TRUE.equals(rule.getInheritChildren()) && ancestors.contains(ruleDirectory));
            if (!applicable) {
                continue;
            }
            allow |= "allow".equals(rule.getEffect());
            deny |= "deny".equals(rule.getEffect());
        }
        return allow && !deny || !allow && !deny;
    }

    /**
     * 处理{@code allowedRoot}并返回对应结果。
     *
     * @param rules {@code rules}参数
     * @param permission 权限参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean allowedRoot(List<AgentKnowledgeDirectoryAcl> rules, String permission) {
        boolean allow = false;
        boolean deny = false;
        for (AgentKnowledgeDirectoryAcl rule : rules) {
            if (!permission.equals(rule.getPermission()) || rule.getDirectoryId() != null) {
                continue;
            }
            allow |= "allow".equals(rule.getEffect());
            deny |= "deny".equals(rule.getEffect());
        }
        return allow && !deny || !allow && !deny;
    }

    /**
     * 校验{@code Manager}，并在条件不满足时终止处理。
     *
     * @param baseId 资源标识
     * @param actor {@code actor}参数
     */
    private void requireManager(Long baseId, CurrentPrincipal actor) {
        AgentKnowledgeBase base = catalogMapper.selectBaseById(baseId);
        if (base == null) {
            throw new ServiceException("知识库不存在", HttpStatus.NOT_FOUND);
        }
        if (!isOwnerOrAdmin(actor, base)) {
            throw new ServiceException("只有知识库所有者或平台管理员可以维护目录 ACL", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 判断{@code OwnerOrAdmin}是否满足要求。
     *
     * @param principal 当前操作主体
     * @param base {@code base}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isOwnerOrAdmin(CurrentPrincipal principal, AgentKnowledgeBase base) {
        return principal != null && (principal.hasRole(PlatformRole.PLATFORM_ADMIN)
            || principal.id().equals(base.getOwnerId()));
    }

    /**
     * 校验目录，并在条件不满足时终止处理。
     *
     * @param baseId 资源标识
     * @param directoryId 资源标识
     */
    private void requireDirectory(Long baseId, Long directoryId) {
        AgentKnowledgeDirectory directory = catalogMapper.selectDirectoryById(directoryId);
        if (directory == null || !baseId.equals(directory.getKnowledgeBaseId())) {
            throw new ServiceException("知识目录不存在", HttpStatus.NOT_FOUND);
        }
    }

    /**
     * 处理{@code enumValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param allowed {@code allowed}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String enumValue(String value, Set<String> allowed, String label) {
        String normalized = value == null ? "" : value.strip();
        if (!allowed.contains(normalized)) {
            throw badRequest(label + "无效");
        }
        return normalized;
    }

    /**
     * 处理{@code summary}并返回对应结果。
     *
     * @param acl {@code acl}参数
     * @return 处理结果
     */
    private String summary(AgentKnowledgeDirectoryAcl acl) {
        return "baseId=" + acl.getKnowledgeBaseId() + ";directoryId=" + acl.getDirectoryId()
            + ";userId=" + acl.getUserId() + ";permission=" + acl.getPermission()
            + ";effect=" + acl.getEffect() + ";inherit=" + acl.getInheritChildren();
    }

    /**
     * 处理{@code badRequest}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 处理{@code conflict}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    /**
     * 封装目录Access相关的不可变数据。
     */
    public record DirectoryAccess(Set<Long> directoryIds, boolean rootAllowed, boolean allDirectories) {
        /**
         * 创建 {@code DirectoryAccess} 实例并初始化所需依赖。
         *
         * @param directoryIds 资源标识集合
         * @param rootAllowed {@code rootAllowed}参数
         * @param allDirectories {@code allDirectories}参数
         */
        public DirectoryAccess {
            directoryIds = Set.copyOf(directoryIds);
        }

        /**
         * 处理{@code all}并返回对应结果。
         *
         * @return 处理结果
         */
        public static DirectoryAccess all() {
            return new DirectoryAccess(Set.of(), true, true);
        }

        /**
         * 处理{@code denyAll}并返回对应结果。
         *
         * @return 处理结果
         */
        public static DirectoryAccess denyAll() {
            return new DirectoryAccess(Set.of(), false, false);
        }

        /**
         * 处理{@code allows}并返回对应结果。
         *
         * @param directoryId 资源标识
         * @return 判断结果，{@code true} 表示条件成立
         */
        public boolean allows(Long directoryId) {
            return allDirectories || (directoryId == null ? rootAllowed : directoryIds.contains(directoryId));
        }
    }
}
