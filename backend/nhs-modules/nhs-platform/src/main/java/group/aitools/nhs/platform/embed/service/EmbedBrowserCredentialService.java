package group.aitools.nhs.platform.embed.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.embed.domain.EmbedBrowserCredential;
import group.aitools.nhs.platform.embed.mapper.EmbedChatMapper;
import group.aitools.nhs.platform.embed.web.EmbedBrowserCredentialView;
import group.aitools.nhs.platform.identity.service.ApiCredentialAuthenticator;
import group.aitools.nhs.platform.identity.service.AuthenticatedServiceAccount;
import group.aitools.nhs.platform.identity.service.EmbedApplicationConfig;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 负责嵌入式会话浏览器凭据相关的业务编排与领域规则处理。
 * Issues and authenticates opaque Embed capabilities without exposing API secrets to an iframe. */
@Service
public class EmbedBrowserCredentialService {

    public static final String PROTOCOL_VERSION = "1.0";
    private static final Pattern TOKEN = Pattern.compile("ebt_[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmbedChatMapper mapper;
    private final PlatformIdGenerator idGenerator;
    private final ApiCredentialAuthenticator apiAuthenticator;
    private final EmbedApplicationPolicy policy;

    public EmbedBrowserCredentialService(
        EmbedChatMapper mapper,
        PlatformIdGenerator idGenerator,
        ApiCredentialAuthenticator apiAuthenticator,
        EmbedApplicationPolicy policy
    ) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.apiAuthenticator = apiAuthenticator;
        this.policy = policy;
    }

    /**
     * 判断{@code sueLaunch}是否满足要求。
     *
     * @param authenticated {@code authenticated}参数
     * @param rawOrigin {@code rawOrigin}参数
     * @param agentVersionId 资源标识
     * @param externalUserKey external用户Key参数
     * @param sessionMinutes 会话Minutes参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public EmbedBrowserCredentialView issueLaunch(
        AuthenticatedServiceAccount authenticated,
        String rawOrigin,
        Long agentVersionId,
        String externalUserKey,
        int sessionMinutes
    ) {
        String origin = EmbedApplicationConfig.normalizeOrigin(rawOrigin);
        policy.requireSessionAllowed(authenticated, origin, agentVersionId, sessionMinutes);
        String external = required(externalUserKey, "Embed外部用户标识", 256);
        LocalDateTime now = utcNow();
        return insert(
            authenticated, "launch", origin, agentVersionId, ContentHashing.sha256(external),
            sessionMinutes, null, now.plusMinutes(5), now
        );
    }

    /**
     * 处理{@code authenticateLaunch}并返回对应结果。
     *
     * @param authorization 授权参数
     * @param rawHostOrigin {@code rawHostOrigin}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public BrowserAccess authenticateLaunch(String authorization, String rawHostOrigin) {
        return authenticate(authorization, rawHostOrigin, "launch", null);
    }

    /**
     * 处理authenticate会话并返回对应结果。
     *
     * @param authorization 授权参数
     * @param rawHostOrigin {@code rawHostOrigin}参数
     * @param sessionId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public BrowserAccess authenticateSession(
        String authorization,
        String rawHostOrigin,
        Long sessionId
    ) {
        return authenticate(authorization, rawHostOrigin, "session", sessionId);
    }

    /**
     * 处理{@code consumeLaunch}并返回对应结果。
     *
     * @param access {@code access}参数
     * @param sessionId 资源标识
     * @param sessionExpiresAt 会话ExpiresAt参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public EmbedBrowserCredentialView consumeLaunch(
        BrowserAccess access,
        Long sessionId,
        LocalDateTime sessionExpiresAt
    ) {
        LocalDateTime now = utcNow();
        if (mapper.consumeLaunchCredential(access.credential().getId(), now) != 1) {
            throw new ServiceException("Embed启动凭证已被消费或失效", HttpStatus.UNAUTHORIZED);
        }
        return insert(
            access.authenticated(), "session", access.credential().getHostOrigin(),
            access.credential().getAgentVersionId(), access.credential().getExternalUserHash(),
            access.credential().getSessionMinutes(), sessionId, sessionExpiresAt, now
        );
    }

    /**
     * 处理rotate会话并返回对应结果。
     *
     * @param access {@code access}参数
     * @param sessionId 资源标识
     * @param sessionExpiresAt 会话ExpiresAt参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public EmbedBrowserCredentialView rotateSession(
        BrowserAccess access,
        Long sessionId,
        LocalDateTime sessionExpiresAt
    ) {
        LocalDateTime now = utcNow();
        if (mapper.revokeBrowserCredential(access.credential().getId(), now) != 1) {
            throw new ServiceException("Embed会话凭证已失效", HttpStatus.UNAUTHORIZED);
        }
        return insert(
            access.authenticated(), "session", access.credential().getHostOrigin(),
            access.credential().getAgentVersionId(), access.credential().getExternalUserHash(),
            access.credential().getSessionMinutes(), sessionId, sessionExpiresAt, now
        );
    }

    /**
     * 处理{@code authenticate}并返回对应结果。
     *
     * @param authorization 授权参数
     * @param rawHostOrigin {@code rawHostOrigin}参数
     * @param tokenKind 令牌Kind参数
     * @param sessionId 资源标识
     * @return 处理结果
     */
    private BrowserAccess authenticate(
        String authorization,
        String rawHostOrigin,
        String tokenKind,
        Long sessionId
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String token = bearer(authorization);
        EmbedBrowserCredential credential = mapper.selectBrowserCredential(ContentHashing.sha256(token));
        LocalDateTime now = utcNow();
        if (credential == null || !tokenKind.equals(credential.getTokenKind())
            || credential.getRevokedAt() != null || credential.getConsumedAt() != null
            || !credential.getExpiresAt().isAfter(now)
            || (sessionId != null && !sessionId.equals(credential.getSessionId()))) {
            throw unauthorized();
        }
        String origin = EmbedApplicationConfig.normalizeOrigin(rawHostOrigin);
        if (!origin.equals(credential.getHostOrigin())) {
            throw new ServiceException("Embed凭证与宿主Origin不匹配", HttpStatus.FORBIDDEN);
        }
        AuthenticatedServiceAccount authenticated = apiAuthenticator.authenticateCredential(
            credential.getApiCredentialId()
        );
        if (!credential.getApplicationId().equals(authenticated.applicationId())
            || !credential.getServiceAccountId().equals(authenticated.principal().id())
            || !"embed".equals(authenticated.applicationType())
            || !authenticated.scopes().contains("chat:invoke")) {
            throw unauthorized();
        }
        policy.requireSessionAllowed(
            authenticated, origin, credential.getAgentVersionId(), credential.getSessionMinutes()
        );
        int touched = "session".equals(tokenKind)
            ? mapper.touchBrowserCredentialSliding(
                credential.getId(), credential.getSessionMinutes(), now
            )
            : mapper.touchBrowserCredential(credential.getId(), now);
        if (touched != 1) {
            throw unauthorized();
        }
        if ("session".equals(tokenKind) && credential.getSessionId() != null
            && mapper.touchSessionSliding(
                credential.getSessionId(), credential.getSessionMinutes(), now
            ) != 1) {
            throw unauthorized();
        }
        return new BrowserAccess(credential, authenticated, policy.currentConfig(authenticated));
    }

    /**
     * 创建并保存{@code insert}。
     *
     * @param authenticated {@code authenticated}参数
     * @param tokenKind 令牌Kind参数
     * @param origin {@code origin}参数
     * @param agentVersionId 资源标识
     * @param externalUserHash external用户Hash参数
     * @param sessionMinutes 会话Minutes参数
     * @param sessionId 资源标识
     * @param expiresAt {@code expiresAt}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    private EmbedBrowserCredentialView insert(
        AuthenticatedServiceAccount authenticated,
        String tokenKind,
        String origin,
        Long agentVersionId,
        String externalUserHash,
        int sessionMinutes,
        Long sessionId,
        LocalDateTime expiresAt,
        LocalDateTime now
    ) {
        for (int attempt = 0; attempt < 3; attempt++) {
            String raw = token();
            EmbedBrowserCredential credential = new EmbedBrowserCredential();
            credential.setId(idGenerator.nextId());
            credential.setTokenHash(ContentHashing.sha256(raw));
            credential.setTokenKind(tokenKind);
            credential.setApplicationId(authenticated.applicationId());
            credential.setApiCredentialId(authenticated.credentialId());
            credential.setServiceAccountId(authenticated.principal().id());
            credential.setAgentVersionId(agentVersionId);
            credential.setHostOrigin(origin);
            credential.setExternalUserHash(externalUserHash);
            credential.setSessionMinutes(sessionMinutes);
            credential.setSessionId(sessionId);
            credential.setExpiresAt(expiresAt);
            credential.setCreatedAt(now);
            if (mapper.insertBrowserCredential(credential) == 1) {
                return new EmbedBrowserCredentialView(raw, expiresAt, PROTOCOL_VERSION, "/embed/chat");
            }
        }
        throw new ServiceException("Embed浏览器凭证签发冲突，请重试", HttpStatus.CONFLICT);
    }

    /**
     * 将输入数据转换为{@code ken}。
     *
     * @return 处理结果
     */
    private String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "ebt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 处理{@code bearer}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String bearer(String value) {
        if (value == null || !value.startsWith("Bearer ")) {
            throw unauthorized();
        }
        String token = value.substring(7).strip();
        if (!TOKEN.matcher(token).matches()) {
            throw unauthorized();
        }
        return token;
    }

    /**
     * 校验{@code d}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @param maximum {@code maximum}参数
     * @return 处理结果
     */
    private String required(String value, String label, int maximum) {
        if (value == null || value.isBlank()) {
            throw new ServiceException(label + "不能为空", HttpStatus.BAD_REQUEST);
        }
        String normalized = value.strip();
        if (normalized.length() > maximum || normalized.indexOf('\0') >= 0) {
            throw new ServiceException(label + "过长或包含非法字符", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 处理{@code utcNow}并返回对应结果。
     *
     * @return 处理结果
     */
    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * 处理{@code unauthorized}并返回对应结果。
     *
     * @return 处理结果
     */
    private ServiceException unauthorized() {
        return new ServiceException("Embed浏览器凭证无效或已失效", HttpStatus.UNAUTHORIZED);
    }

    /**
     * 封装浏览器Access相关的不可变数据。
     */
    public record BrowserAccess(
        EmbedBrowserCredential credential,
        AuthenticatedServiceAccount authenticated,
        EmbedApplicationConfig config
    ) {
    }
}
