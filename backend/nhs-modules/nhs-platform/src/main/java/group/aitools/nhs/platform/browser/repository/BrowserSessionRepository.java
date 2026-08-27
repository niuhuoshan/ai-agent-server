package group.aitools.nhs.platform.browser.repository;

import group.aitools.nhs.platform.browser.domain.BrowserSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 提供浏览器会话相关的数据访问能力。
 * JDBC persistence for browser sessions, events, and short worker leases. */
@Repository
public class BrowserSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public BrowserSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 创建并保存会话。
     *
     * @param value {@code value}参数
     */
    public void insertSession(BrowserSession value) {
        jdbcTemplate.update("""
            INSERT INTO agent_browser_session
                (id, owner_id, session_key, worker_session_id, profile_key, status,
                 current_url, page_title, active_tab_id, tab_state_json,
                 handoff_status, handoff_reason, handoff_user_id, handoff_requested_at,
                 handoff_started_at, handoff_returned_at, created_at, updated_at, closed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            value.id(), value.ownerId(), value.sessionKey(), value.workerSessionId(), value.profileKey(),
            value.status(), value.currentUrl(), value.pageTitle(), value.activeTabId(), value.tabStateJson(),
            value.handoffStatus(), value.handoffReason(), value.handoffUserId(), value.handoffRequestedAt(),
            value.handoffStartedAt(), value.handoffReturnedAt(), value.createdAt(), value.updatedAt(), value.closedAt()
        );
    }

    /**
     * 获取{@code Owned}。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @return 处理结果
     */
    public BrowserSession findOwned(Long id, Long ownerId) {
        List<BrowserSession> values = jdbcTemplate.query("""
            SELECT id, owner_id, session_key, worker_session_id, profile_key, status,
                   current_url, page_title, active_tab_id, tab_state_json,
                   handoff_status, handoff_reason, handoff_user_id, handoff_requested_at,
                   handoff_started_at, handoff_returned_at, created_at, updated_at, closed_at
            FROM agent_browser_session
            WHERE id = ? AND owner_id = ?
            """, this::map, id, ownerId);
        return values.isEmpty() ? null : values.getFirst();
    }

    /**
     * 查询{@code Owned}列表。
     *
     * @param ownerId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<BrowserSession> listOwned(Long ownerId, int limit) {
        return jdbcTemplate.query("""
            SELECT id, owner_id, session_key, worker_session_id, profile_key, status,
                   current_url, page_title, active_tab_id, tab_state_json,
                   handoff_status, handoff_reason, handoff_user_id, handoff_requested_at,
                   handoff_started_at, handoff_returned_at, created_at, updated_at, closed_at
            FROM agent_browser_session
            WHERE owner_id = ?
            ORDER BY updated_at DESC, id DESC
            LIMIT ?
            """, this::map, ownerId, limit);
    }

    /**
 * 查询Open工作进程Sessions列表。
 * Lists all platform-owned sessions that still claim to be backed by a Worker. */
    public List<BrowserSession> listOpenWorkerSessions() {
        return jdbcTemplate.query("""
            SELECT id, owner_id, session_key, worker_session_id, profile_key, status,
                   current_url, page_title, active_tab_id, tab_state_json,
                   handoff_status, handoff_reason, handoff_user_id, handoff_requested_at,
                   handoff_started_at, handoff_returned_at, created_at, updated_at, closed_at
            FROM agent_browser_session
            WHERE status = 'open' AND worker_session_id IS NOT NULL
            ORDER BY id
            """, this::map);
    }

    /**
     * 处理{@code markOpened}并返回对应结果。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @param workerSessionId 资源标识
     * @param currentUrl 当前Url参数
     * @param pageTitle {@code pageTitle}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    public int markOpened(Long id, Long ownerId, String workerSessionId, String currentUrl,
                          String pageTitle, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE agent_browser_session
            SET status = 'open', worker_session_id = ?, current_url = ?, page_title = ?,
                updated_at = ?, closed_at = NULL
            WHERE id = ? AND owner_id = ?
            """, workerSessionId, currentUrl, pageTitle, now, id, ownerId);
    }

    /**
     * 更新{@code Page}。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @param currentUrl 当前Url参数
     * @param pageTitle {@code pageTitle}参数
     * @param activeTabId 资源标识
     * @param tabStateJson {@code tabStateJson}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    public int updatePage(Long id, Long ownerId, String currentUrl, String pageTitle,
                          String activeTabId, String tabStateJson, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE agent_browser_session
            SET current_url = COALESCE(?, current_url), page_title = COALESCE(?, page_title),
                active_tab_id = COALESCE(?, active_tab_id), tab_state_json = COALESCE(CAST(? AS jsonb), tab_state_json),
                updated_at = ?
            WHERE id = ? AND owner_id = ? AND status IN ('open', 'opening')
            """, currentUrl, pageTitle, activeTabId, tabStateJson, now, id, ownerId);
    }

    /**
     * 处理{@code markClosed}并返回对应结果。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    public int markClosed(Long id, Long ownerId, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE agent_browser_session
            SET status = 'closed', updated_at = ?, closed_at = ?
            WHERE id = ? AND owner_id = ?
            """, now, now, id, ownerId);
    }

    /**
     * 处理{@code markFailed}并返回对应结果。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    public int markFailed(Long id, Long ownerId, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE agent_browser_session
            SET status = 'failed', updated_at = ?
            WHERE id = ? AND owner_id = ?
            """, now, id, ownerId);
    }

    /**
     * 处理mark工作进程Unavailable并返回对应结果。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    public int markWorkerUnavailable(Long id, Long ownerId, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE agent_browser_session
            SET status = 'failed', handoff_status = 'expired', updated_at = ?
            WHERE id = ? AND owner_id = ? AND status IN ('open', 'opening', 'closing')
            """, now, id, ownerId);
    }

    /**
     * 处理{@code requestHandoff}并返回对应结果。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @param reason {@code reason}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    public int requestHandoff(Long id, Long ownerId, String reason, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE agent_browser_session
            SET handoff_status = 'requested', handoff_reason = ?, handoff_user_id = NULL,
                handoff_requested_at = ?, handoff_started_at = NULL, handoff_returned_at = NULL,
                updated_at = ?
            WHERE id = ? AND owner_id = ? AND status = 'open'
              AND handoff_status IN ('none', 'returned', 'expired')
            """, reason, now, now, id, ownerId);
    }

    /**
     * 处理{@code takeHandoff}并返回对应结果。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @param handoffUserId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    public int takeHandoff(Long id, Long ownerId, Long handoffUserId, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE agent_browser_session
            SET handoff_status = 'human_control', handoff_user_id = ?, handoff_started_at = ?, updated_at = ?
            WHERE id = ? AND owner_id = ? AND status = 'open' AND handoff_status = 'requested'
            """, handoffUserId, now, now, id, ownerId);
    }

    /**
     * 处理{@code returnHandoff}并返回对应结果。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    public int returnHandoff(Long id, Long ownerId, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE agent_browser_session
            SET handoff_status = 'returned', handoff_returned_at = ?, updated_at = ?
            WHERE id = ? AND owner_id = ? AND status = 'open' AND handoff_status IN ('requested', 'human_control')
            """, now, now, id, ownerId);
    }

    /**
     * 创建并保存事件。
     *
     * @param id 资源标识
     * @param sessionId 资源标识
     * @param ownerId 资源标识
     * @param eventType 业务类型
     * @param status 目标状态
     * @param requestJson {@code requestJson}参数
     * @param responseJson {@code responseJson}参数
     * @param errorMessage 待处理内容
     * @param createdAt {@code createdAt}参数
     */
    public void insertEvent(Long id, Long sessionId, Long ownerId, String eventType, String status,
                            String requestJson, String responseJson, String errorMessage,
                            LocalDateTime createdAt) {
        jdbcTemplate.update("""
            INSERT INTO agent_browser_event
                (id, session_id, owner_id, event_type, status, request_json, response_json,
                 error_message, created_at)
            VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)
            """, id, sessionId, ownerId, eventType, status, requestJson, responseJson,
            errorMessage, createdAt);
    }

    /**
     * 处理{@code upsertLease}相关逻辑。
     *
     * @param id 资源标识
     * @param sessionId 资源标识
     * @param ownerId 资源标识
     * @param workerId 资源标识
     * @param leaseToken lease令牌参数
     * @param leaseUntil {@code leaseUntil}参数
     * @param now {@code now}参数
     */
    public void upsertLease(Long id, Long sessionId, Long ownerId, String workerId,
                            String leaseToken, LocalDateTime leaseUntil, LocalDateTime now) {
        jdbcTemplate.update("""
            INSERT INTO agent_browser_lease
                (id, session_id, owner_id, worker_id, lease_token, lease_until, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (session_id) DO UPDATE SET
                owner_id = EXCLUDED.owner_id, worker_id = EXCLUDED.worker_id,
                lease_token = EXCLUDED.lease_token, lease_until = EXCLUDED.lease_until,
                updated_at = EXCLUDED.updated_at
            """, id, sessionId, ownerId, workerId, leaseToken, leaseUntil, now, now);
    }

    /**
     * 删除{@code Lease}。
     *
     * @param sessionId 资源标识
     * @param ownerId 资源标识
     */
    public void deleteLease(Long sessionId, Long ownerId) {
        jdbcTemplate.update("DELETE FROM agent_browser_lease WHERE session_id = ? AND owner_id = ?",
            sessionId, ownerId);
    }

    /**
     * 将输入数据转换为{@code map}。
     *
     * @param resultSet 结果Set参数
     * @param rowNum {@code rowNum}参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private BrowserSession map(ResultSet resultSet, int rowNum) throws SQLException {
        return new BrowserSession(
            resultSet.getLong("id"),
            resultSet.getLong("owner_id"),
            resultSet.getString("session_key"),
            resultSet.getString("worker_session_id"),
            resultSet.getString("profile_key"),
            resultSet.getString("status"),
            resultSet.getString("current_url"),
            resultSet.getString("page_title"),
            resultSet.getString("active_tab_id"),
            resultSet.getString("tab_state_json"),
            resultSet.getString("handoff_status"),
            resultSet.getString("handoff_reason"),
            nullableLong(resultSet, "handoff_user_id"),
            date(resultSet, "handoff_requested_at"),
            date(resultSet, "handoff_started_at"),
            date(resultSet, "handoff_returned_at"),
            date(resultSet, "created_at"),
            date(resultSet, "updated_at"),
            date(resultSet, "closed_at")
        );
    }

    /**
     * 处理{@code date}并返回对应结果。
     *
     * @param resultSet 结果Set参数
     * @param column {@code column}参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private LocalDateTime date(ResultSet resultSet, String column) throws SQLException {
        java.sql.Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    /**
     * 处理{@code nullableLong}并返回对应结果。
     *
     * @param resultSet 结果Set参数
     * @param column {@code column}参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }
}
