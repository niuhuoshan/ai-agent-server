package group.aitools.nhs.platform.embed.service;

import group.aitools.nhs.platform.embed.service.EmbedBrowserCredentialService.BrowserAccess;
import group.aitools.nhs.platform.embed.web.EmbedBrowserCredentialView;
import group.aitools.nhs.platform.embed.web.EmbedSessionView;
import group.aitools.nhs.platform.embed.web.EmbedWidgetBootstrapView;
import group.aitools.nhs.platform.embed.web.EmbedWidgetStateView;
import group.aitools.nhs.platform.embed.web.EmbedTurnView;
import group.aitools.nhs.platform.embed.web.EmbedMessageView;
import group.aitools.nhs.platform.agent.service.AgentWelcomeCardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;

import java.util.List;

/**
 * 负责嵌入式会话Widget会话相关的业务编排与领域规则处理。
 */
@Service
public class EmbedWidgetSessionService {

    private final EmbedChatPersistenceService persistence;
    private final EmbedBrowserCredentialService credentials;
    private final EmbedChatExecutionCoordinator coordinator;
    private final AgentWelcomeCardService welcomeCardService;

    /**
     * 创建 {@code EmbedWidgetSessionService} 实例并初始化所需依赖。
     *
     * @param persistence {@code persistence}参数
     * @param credentials {@code credentials}参数
     * @param coordinator {@code coordinator}参数
     * @param welcomeCardService {@code welcomeCardService}参数
     */
    @Autowired
    public EmbedWidgetSessionService(
        EmbedChatPersistenceService persistence,
        EmbedBrowserCredentialService credentials,
        EmbedChatExecutionCoordinator coordinator,
        AgentWelcomeCardService welcomeCardService
    ) {
        this.persistence = persistence;
        this.credentials = credentials;
        this.coordinator = coordinator;
        this.welcomeCardService = welcomeCardService;
    }

    /**
 * 创建 {@code EmbedWidgetSessionService} 实例并初始化所需依赖。
 * Backwards-compatible constructor for focused unit tests and older embedders. */
    EmbedWidgetSessionService(
        EmbedChatPersistenceService persistence,
        EmbedBrowserCredentialService credentials,
        EmbedChatExecutionCoordinator coordinator
    ) {
        this(persistence, credentials, coordinator, null);
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param access {@code access}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public EmbedWidgetBootstrapView create(BrowserAccess access) {
        EmbedSessionView session = persistence.createSessionWithHash(
            access.authenticated(), access.credential().getAgentVersionId(),
            access.credential().getExternalUserHash(), access.credential().getSessionMinutes()
        );
        EmbedBrowserCredentialView browserCredential = credentials.consumeLaunch(
            access, session.id(), session.expiresAt()
        );
        return new EmbedWidgetBootstrapView(
            session, browserCredential, access.config(),
            welcomeCards(access)
        );
    }

    /**
     * 清理或重置{@code reset}。
     *
     * @param access {@code access}参数
     * @param oldSessionId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public EmbedWidgetBootstrapView reset(BrowserAccess access, Long oldSessionId) {
        var oldSession = persistence.ownedSession(access.authenticated(), oldSessionId);
        if (!access.credential().getExternalUserHash().equals(oldSession.getExternalUserHash())) {
            throw new ServiceException("Embed会话与启动凭证不匹配", HttpStatus.FORBIDDEN);
        }
        List<Long> activeTurns = persistence.requestSessionStopsForReset(
            access.authenticated(), oldSessionId
        );
        cancelAfterCommit(activeTurns);
        persistence.closeSessionForReset(access.authenticated(), oldSessionId);
        EmbedSessionView session = persistence.createSessionWithHash(
            access.authenticated(), access.credential().getAgentVersionId(),
            access.credential().getExternalUserHash(), access.credential().getSessionMinutes()
        );
        EmbedBrowserCredentialView browserCredential = credentials.rotateSession(
            access, session.id(), session.expiresAt()
        );
        return new EmbedWidgetBootstrapView(
            session, browserCredential, access.config(),
            welcomeCards(access)
        );
    }

    /**
     * 处理{@code welcomeCards}并返回对应结果。
     *
     * @param access {@code access}参数
     * @return 符合条件的数据集合
     */
    private List<group.aitools.nhs.platform.agent.web.WelcomeCardView> welcomeCards(BrowserAccess access) {
        return welcomeCardService == null
            ? List.of()
            : welcomeCardService.listForVersion(access.credential().getAgentVersionId());
    }

    /**
     * 判断{@code celAfterCommit}是否满足要求。
     *
     * @param turnIds 资源标识集合
     */
    private void cancelAfterCommit(List<Long> turnIds) {
        if (turnIds.isEmpty()) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            turnIds.forEach(turnId -> coordinator.requestStop(turnId, "Embed会话重置"));
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 处理{@code afterCommit}相关逻辑。
             */
            @Override
            public void afterCommit() {
                turnIds.forEach(turnId -> coordinator.requestStop(
                    turnId, "Embed会话重置"
                ));
            }
        });
    }

    /**
     * 处理{@code state}并返回对应结果。
     *
     * @param access {@code access}参数
     * @param sessionId 资源标识
     * @return 处理结果
     */
    public EmbedWidgetStateView state(BrowserAccess access, Long sessionId) {
        var session = persistence.ownedActiveSession(access.authenticated(), sessionId);
        EmbedSessionView sessionView = new EmbedSessionView(
            session.getId(), session.getAgentVersionId(), session.getStatus(),
            session.getExpiresAt(), session.getCreatedAt()
        );
        return new EmbedWidgetStateView(
            sessionView,
            persistence.turns(access.authenticated(), sessionId, 50).stream()
                .map(EmbedTurnView::from).toList(),
            persistence.messages(access.authenticated(), sessionId, 200).stream()
                .map(EmbedMessageView::from).toList()
        );
    }
}
