package group.aitools.nhs.platform.portal.data.service;

import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.mapper.AgentConversationMapper;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.report.domain.AgentReport;
import group.aitools.nhs.platform.report.domain.AgentReportRun;
import group.aitools.nhs.platform.report.domain.AgentReportSubscription;
import group.aitools.nhs.platform.report.mapper.AgentReportMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the Nhs data-portal home projection from report and private-chat facts. */
@Service
public class PortalDataPortalService {

    private final CurrentPrincipalProvider principalProvider;
    private final AgentReportMapper reportMapper;
    private final AgentConversationMapper conversationMapper;

    public PortalDataPortalService(
        CurrentPrincipalProvider principalProvider,
        AgentReportMapper reportMapper,
        AgentConversationMapper conversationMapper
    ) {
        this.principalProvider = principalProvider;
        this.reportMapper = reportMapper;
        this.conversationMapper = conversationMapper;
    }

    public Map<String, Object> home() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        boolean admin = principal.hasRole(PlatformRole.PLATFORM_ADMIN);
        List<AgentReport> reports = reportMapper.selectVisible(
            principal.id(), admin, "active", null, 500
        );
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime today = now.toLocalDate().atStartOfDay();
        List<Map<String, Object>> reportItems = new ArrayList<>();
        List<Map<String, Object>> recent = new ArrayList<>();
        int failedToday = 0;
        Map<String, AgentReportSubscription> ownSubscriptions = new LinkedHashMap<>();

        for (AgentReport report : reports) {
            boolean owner = report.getOwnerId() != null && report.getOwnerId().equals(principal.id());
            AgentReportSubscription subscription = ownSubscription(report.getId(), principal.id());
            if (subscription != null) {
                ownSubscriptions.put(String.valueOf(report.getId()), subscription);
            }

            // Shared report runs do not carry an owner column.  Do not expose
            // their execution history to another user rather than guessing.
            List<AgentReportRun> runs = owner
                ? reportMapper.selectRuns(report.getId(), 30)
                : List.of();
            AgentReportRun latest = runs.stream()
                .filter(run -> run.getStartedAt() != null || run.getCreatedAt() != null)
                .max(Comparator.comparing(this::runTime, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
            String lastRunAt = latest == null || runTime(latest) == null ? null : runTime(latest).toString();
            String lastError = latest == null ? null : latest.getErrorSummary();
            if (owner) {
                for (AgentReportRun run : runs) {
                    LocalDateTime occurred = runTime(run);
                    if (occurred != null && !occurred.isBefore(today)
                        && isFailure(run.getStatus())) {
                        failedToday++;
                    }
                    if (occurred != null) {
                        recent.add(activity(report, run, occurred));
                    }
                }
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(report.getId()));
            item.put("title", report.getName());
            item.put("owner_name", report.getOwnerId() == null ? null : String.valueOf(report.getOwnerId()));
            item.put("is_owner", owner);
            item.put("is_favorite", false);
            item.put("pinned", false);
            item.put("last_run_at", lastRunAt);
            item.put("last_error", lastError);
            item.put("subscription_status", subscription == null ? null : subscription.getStatus());
            item.put("subscription_cron_expr", subscription == null ? null : subscription.getCronExpr());
            item.put("subscription_next_run_at", subscription == null || subscription.getNextRunAt() == null
                ? null : subscription.getNextRunAt().toString());
            reportItems.add(item);
        }

        for (AgentConversation conversation : conversationMapper.selectRecentOwnedConversations(principal.id(), 30)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "conversation");
            item.put("id", conversation.getId());
            item.put("conversation_id", String.valueOf(conversation.getId()));
            item.put("title", conversation.getTitle() == null || conversation.getTitle().isBlank()
                ? "未命名数据分析" : conversation.getTitle());
            item.put("subtitle", "ChatBI · 数据分析");
            item.put("status", conversation.getStatus() == null ? "success" : conversation.getStatus());
            item.put("occurred_at", conversation.getLastMessageAt() == null
                ? conversation.getCreateTime() : conversation.getLastMessageAt());
            item.put("action", "open_conversation");
            recent.add(item);
        }
        recent.sort(Comparator.comparing(item -> String.valueOf(item.get("occurred_at")), Comparator.reverseOrder()));

        reportItems.sort(Comparator.comparing(
            item -> String.valueOf(item.get("last_run_at")), Comparator.reverseOrder()
        ));
        Map<String, Object> attention = new LinkedHashMap<>();
        attention.put("failed_runs_today", failedToday);
        attention.put("latest_failed_run", latestFailure(reports, principal.id(), today));
        // There is no Nhs digest-delivery table in this platform.  Scheduled
        // report runs remain visible as report_run activities; digest counts are
        // deliberately zero instead of presenting a report run as a delivered digest.
        attention.put("digests_today", 0);
        attention.put("latest_digest_at", null);
        attention.put("active_subscriptions", ownSubscriptions.values().stream()
            .filter(item -> "active".equals(item.getStatus())).count());
        attention.put("completed_subscriptions_today", 0);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("subscribed", reportItems.stream().filter(item -> item.get("subscription_status") != null).count());
        summary.put("pinned", 0);
        summary.put("favorite", 0);
        summary.put("shared", reportItems.stream().filter(item -> !Boolean.TRUE.equals(item.get("is_owner"))).count());
        summary.put("recent", reportItems.stream().filter(item -> item.get("last_run_at") != null).count());
        summary.put("items", reportItems.stream().limit(12).toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attention", attention);
        result.put("recent_analysis", recent.stream().limit(6).toList());
        result.put("report_summary", summary);
        result.put("generated_at", now.toString());
        return result;
    }

    private AgentReportSubscription ownSubscription(Long reportId, Long userId) {
        return reportMapper.selectSubscriptions(reportId).stream()
            .filter(item -> userId.equals(item.getCreateBy()))
            .findFirst()
            .orElse(null);
    }

    private Map<String, Object> activity(AgentReport report, AgentReportRun run, LocalDateTime occurred) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "report_run");
        item.put("id", run.getId());
        item.put("report_id", String.valueOf(report.getId()));
        item.put("run_id", run.getId());
        item.put("title", report.getName());
        item.put("subtitle", "数据报表 · " + (run.getRowCount() == null ? 0 : run.getRowCount()) + " 行");
        item.put("status", run.getStatus());
        item.put("occurred_at", occurred);
        item.put("action", "open_report");
        return item;
    }

    private Map<String, Object> latestFailure(List<AgentReport> reports, Long userId, LocalDateTime today) {
        AgentReportRun latest = null;
        AgentReport report = null;
        for (AgentReport candidate : reports) {
            if (candidate.getOwnerId() == null || !candidate.getOwnerId().equals(userId)) {
                continue;
            }
            for (AgentReportRun run : reportMapper.selectRuns(candidate.getId(), 30)) {
                LocalDateTime occurred = runTime(run);
                if (occurred == null || occurred.isBefore(today) || !isFailure(run.getStatus())) {
                    continue;
                }
                if (latest == null || occurred.isAfter(runTime(latest))) {
                    latest = run;
                    report = candidate;
                }
            }
        }
        if (latest == null || report == null) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("report_id", String.valueOf(report.getId()));
        value.put("run_id", latest.getId());
        value.put("title", report.getName());
        value.put("error_message", latest.getErrorSummary());
        value.put("occurred_at", runTime(latest));
        return value;
    }

    private LocalDateTime runTime(AgentReportRun run) {
        return run.getFinishedAt() != null ? run.getFinishedAt()
            : run.getStartedAt() != null ? run.getStartedAt() : run.getCreatedAt();
    }

    private boolean isFailure(String status) {
        return "failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status);
    }
}
