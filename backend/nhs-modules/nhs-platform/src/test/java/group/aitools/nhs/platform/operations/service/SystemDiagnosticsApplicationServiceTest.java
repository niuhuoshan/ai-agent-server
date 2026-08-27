package group.aitools.nhs.platform.operations.service;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.operations.web.SystemDiagnosticCheckView;
import group.aitools.nhs.platform.operations.web.SystemDiagnosticsView;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
class SystemDiagnosticsApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T05:30:00Z");
    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER, PlatformRole.PLATFORM_ADMIN)
    );

    private CurrentPrincipalProvider principalProvider;
    private ObjectProvider<DataSource> dataSourceProvider;
    private AgentAuditEventMapper auditMapper;
    private PlatformIdGenerator idGenerator;
    private DataSource dataSource;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        principalProvider = mock(CurrentPrincipalProvider.class);
        dataSourceProvider = mock(ObjectProvider.class);
        auditMapper = mock(AgentAuditEventMapper.class);
        idGenerator = mock(PlatformIdGenerator.class);
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);

        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        when(dataSourceProvider.getIfAvailable()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        when(idGenerator.nextId()).thenReturn(100L);
    }

    @Test
    void reportsHealthyRequiredChecksFromPersistedOperationalFacts() throws Exception {
        stubQueries("64", 0, 0, 0, 0, 0, 1, 0, 1, 0, 0);

        SystemDiagnosticsView result = service(false).diagnostics();

        assertThat(result.status()).isEqualTo("healthy");
        assertThat(result.ready()).isTrue();
        assertThat(result.checkedAt()).isEqualTo(NOW);
        assertThat(result.checks()).extracting(SystemDiagnosticCheckView::key)
            .containsExactly("schema", "outbox", "leases", "sandbox", "providers", "search");
        assertThat(check(result, "schema").metrics())
            .containsEntry("currentVersion", "64")
            .containsEntry("expectedVersion", "64");
    }

    @Test
    void schemaLagMakesReadinessUnavailable() throws Exception {
        stubQueries("63", 0, 0, 0, 0, 0, 1, 0, 0, 0, 0);

        SystemDiagnosticsView result = service(false).diagnostics();

        assertThat(result.status()).isEqualTo("unavailable");
        assertThat(check(result, "schema").message()).contains("低于");
    }

    @Test
    void optionalSandboxCanBeDisabledWithoutBlockingReadiness() throws Exception {
        stubQueries("64", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        SystemDiagnosticsView result = service(false).diagnostics();

        assertThat(result.status()).isEqualTo("healthy");
        assertThat(check(result, "sandbox").status()).isEqualTo("disabled");
        assertThat(check(result, "sandbox").required()).isFalse();
    }

    @Test
    void requiredSandboxWithoutRunnerBlocksReadiness() throws Exception {
        stubQueries("64", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        SystemDiagnosticsView result = service(true).diagnostics();

        assertThat(result.status()).isEqualTo("unavailable");
        assertThat(check(result, "sandbox").required()).isTrue();
    }

    @Test
    void rejectsNonAdministratorsBeforeOpeningDatabaseConnection() {
        when(principalProvider.currentPrincipal()).thenReturn(new CurrentPrincipal(
            2L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        ));

        assertThatThrownBy(() -> service(false).diagnostics())
            .isInstanceOf(ServiceException.class)
            .extracting(exception -> ((ServiceException) exception).getCode())
            .isEqualTo(403);
        verifyNoInteractions(dataSourceProvider);
    }

    @Test
    void makesAuditPersistenceFailureVisibleInDiagnosticResult() {
        when(dataSourceProvider.getIfAvailable()).thenReturn(null);
        when(auditMapper.insertEvent(
            org.mockito.ArgumentMatchers.any(), anyString(), org.mockito.ArgumentMatchers.any(), anyString(),
            anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), anyString(),
            anyString(), anyString(), org.mockito.ArgumentMatchers.any()
        )).thenThrow(new IllegalStateException("database unavailable"));

        SystemDiagnosticsView result = service(false).diagnostics();

        assertThat(result.status()).isEqualTo("unavailable");
        assertThat(result.checks()).extracting(SystemDiagnosticCheckView::key).contains("diagnosticAudit");
    }

    private void stubQueries(
        String version,
        long pending,
        long failed,
        long due,
        long taskExpired,
        long sandboxExpired,
        long activeRunners,
        long staleRunners,
        long providerCount,
        long searchOpen,
        long searchHalfOpen
    ) throws Exception {
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("flyway_schema_history")) {
                return statement(List.of(row("version", version, "success", true)));
            }
            if (sql.contains("agent_outbox_event")) {
                return statement(List.of(row("pending", pending, "failed", failed, "due", due)));
            }
            if (sql.contains("agent_task_run")) {
                return statement(List.of(row(
                    "task_expired", taskExpired,
                    "sandbox_expired", sandboxExpired,
                    "report_expired", 0L
                )));
            }
            if (sql.contains("agent_sandbox_runner")) {
                return statement(List.of(row("active", activeRunners, "stale", staleRunners)));
            }
            if (sql.contains("FROM agent_connector")) {
                return statement(providerCount == 0
                    ? List.of()
                    : List.of(row("provider_type", "mcp", "provider_count", providerCount)));
            }
            if (sql.contains("agent_search_provider_state")) {
                return statement(List.of(row(
                    "open_count", searchOpen,
                    "half_open_count", searchHalfOpen,
                    "tracked_count", searchOpen + searchHalfOpen + (searchOpen == 0 && searchHalfOpen == 0 ? 1 : 0)
                )));
            }
            throw new IllegalArgumentException("Unexpected SQL: " + sql);
        });
    }

    private PreparedStatement statement(List<Map<String, Object>> rows) throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        AtomicInteger index = new AtomicInteger(-1);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenAnswer(invocation -> index.incrementAndGet() < rows.size());
        when(result.getString(anyString())).thenAnswer(invocation -> String.valueOf(
            rows.get(index.get()).get(invocation.getArgument(0, String.class))
        ));
        when(result.getBoolean(anyString())).thenAnswer(invocation -> Boolean.TRUE.equals(
            rows.get(index.get()).get(invocation.getArgument(0, String.class))
        ));
        when(result.getLong(anyString())).thenAnswer(invocation -> {
            Object value = rows.get(index.get()).get(invocation.getArgument(0, String.class));
            return value instanceof Number number ? number.longValue() : 0L;
        });
        return statement;
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            row.put(String.valueOf(values[index]), values[index + 1]);
        }
        return row;
    }

    private SystemDiagnosticsApplicationService service(boolean sandboxRequired) {
        return new SystemDiagnosticsApplicationService(
            principalProvider, dataSourceProvider, auditMapper, idGenerator,
            sandboxRequired, "64", Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private SystemDiagnosticCheckView check(SystemDiagnosticsView result, String key) {
        return result.checks().stream().filter(check -> key.equals(check.key())).findFirst().orElseThrow();
    }
}
