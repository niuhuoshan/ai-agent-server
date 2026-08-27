package group.aitools.nhs.platform.nhs.portal.example;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;

/**
 * 负责门户Example审计相关的业务编排与领域规则处理。
 * Writes independent audit and immutable content facts for example mutations. */
@Service
public class PortalExampleAuditService {

    private static final int SUMMARY_LIMIT = 1_000;

    private final AgentAuditEventMapper auditMapper;
    private final AgentChatBIExampleRevisionMapper revisionMapper;
    private final PlatformIdGenerator idGenerator;

    public PortalExampleAuditService(
        AgentAuditEventMapper auditMapper,
        AgentChatBIExampleRevisionMapper revisionMapper,
        PlatformIdGenerator idGenerator
    ) {
        this.auditMapper = auditMapper;
        this.revisionMapper = revisionMapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 处理{@code record}相关逻辑。
     *
     * @param principal 当前操作主体
     * @param action {@code action}参数
     * @param example {@code example}参数
     * @param reason {@code reason}参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(CurrentPrincipal principal, String action, AgentChatBIExample example, String reason) {
        recordOutcome(principal, action, example, reason, "success");
    }

    /**
     * 处理{@code recordFailure}相关逻辑。
     *
     * @param principal 当前操作主体
     * @param action {@code action}参数
     * @param example {@code example}参数
     * @param reason {@code reason}参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(CurrentPrincipal principal, String action, AgentChatBIExample example, String reason) {
        recordOutcome(principal, action, example, reason, "failure");
    }

    /**
     * 处理{@code recordOutcome}相关逻辑。
     *
     * @param principal 当前操作主体
     * @param action {@code action}参数
     * @param example {@code example}参数
     * @param reason {@code reason}参数
     * @param decision {@code decision}参数
     */
    private void recordOutcome(
        CurrentPrincipal principal,
        String action,
        AgentChatBIExample example,
        String reason,
        String decision
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (principal == null || example == null || example.getId() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String actorType = principal.type() == PrincipalType.HUMAN ? "user" : "service_account";
        AgentChatBIExampleRevision revision = new AgentChatBIExampleRevision();
        revision.setId(idGenerator.nextId());
        revision.setExampleId(example.getId());
        revision.setAction(action);
        revision.setReviewStatus(value(example.getReviewStatus(), "pending"));
        revision.setUserQuery(value(example.getUserQuery(), ""));
        revision.setRefinedQuery(example.getRefinedQuery());
        revision.setContextSummary(example.getContextSummary());
        revision.setSqlText(value(example.getSqlText(), ""));
        revision.setSqlMetadataJson(value(example.getSqlMetadataJson(), "{}"));
        revision.setCategory(value(example.getCategory(), "general"));
        revision.setEnhanceStatus(value(example.getEnhanceStatus(), "not_requested"));
        revision.setLocalSyncStatus(value(example.getLocalSyncStatus(), "pending"));
        revision.setActorType(actorType);
        revision.setActorId(principal.id());
        revision.setReason(bounded(reason, 512));
        revision.setContentHash(hash(revision));
        revision.setCreatedAt(now);
        if (revisionMapper.insert(revision) != 1) {
            throw new IllegalStateException("案例版本历史写入失败");
        }
        int audited = auditMapper.insertEvent(
            idGenerator.nextId(), actorType, principal.id(), bounded("chatbi_example_" + action, 64),
            "chatbi_example", example.getId(), null, decision, bounded(reason, SUMMARY_LIMIT),
            bounded(summary(example), SUMMARY_LIMIT), now
        );
        if (audited != 1) {
            throw new IllegalStateException("案例操作审计写入失败");
        }
    }

    /**
     * 处理{@code summary}并返回对应结果。
     *
     * @param example {@code example}参数
     * @return 处理结果
     */
    private String summary(AgentChatBIExample example) {
        return "trace=" + bounded(example.getTraceId(), 128)
            + ",review=" + bounded(example.getReviewStatus(), 32)
            + ",sync=" + bounded(example.getLocalSyncStatus(), 32);
    }

    /**
     * 处理{@code value}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    private String value(String value, String fallback) {
        return value == null ? fallback : value;
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @return 处理结果
     */
    private String bounded(String value, int max) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace('\0', ' ').replace('\r', ' ').replace('\n', ' ').strip();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    /**
     * 判断{@code h}是否满足要求。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String hash(AgentChatBIExampleRevision value) {
        try {
            String source = value(value.getUserQuery(), "") + "\n"
                + value(value.getRefinedQuery(), "") + "\n"
                + value(value.getContextSummary(), "") + "\n"
                + value(value.getSqlText(), "") + "\n"
                + value(value.getSqlMetadataJson(), "") + "\n"
                + value(value.getCategory(), "general");
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("案例内容指纹计算失败", exception);
        }
    }
}
