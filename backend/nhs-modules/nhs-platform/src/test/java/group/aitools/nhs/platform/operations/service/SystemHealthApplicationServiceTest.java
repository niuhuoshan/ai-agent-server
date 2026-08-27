package group.aitools.nhs.platform.operations.service;

import group.aitools.nhs.runtime.spi.AgentRuntime;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.operations.web.SystemHealthComponentView;
import group.aitools.nhs.platform.operations.web.SystemHealthOverviewView;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
class SystemHealthApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T04:30:00Z");
    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER, PlatformRole.PLATFORM_ADMIN)
    );

    private CurrentPrincipalProvider principalProvider;
    private ObjectProvider<DataSource> dataSourceProvider;
    private ObjectProvider<RedissonClient> redisProvider;
    private ObjectProvider<AgentRuntime> runtimeProvider;
    private DataSource dataSource;
    private RedissonClient redis;
    private AgentRuntime runtime;

    @BeforeEach
    void setUp() throws Exception {
        principalProvider = mock(CurrentPrincipalProvider.class);
        dataSourceProvider = mock(ObjectProvider.class);
        redisProvider = mock(ObjectProvider.class);
        runtimeProvider = mock(ObjectProvider.class);
        dataSource = mock(DataSource.class);
        redis = mock(RedissonClient.class);
        runtime = mock(AgentRuntime.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        RKeys keys = mock(RKeys.class);

        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        when(dataSourceProvider.getIfAvailable()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(metadata.getDatabaseProductVersion()).thenReturn("17.1");
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        when(redis.getKeys()).thenReturn(keys);
        when(keys.count()).thenReturn(42L);
        when(runtimeProvider.getIfAvailable()).thenReturn(runtime);
    }

    @Test
    void reportsHealthySnapshotFromRealComponentProbes() {
        SystemHealthOverviewView result = service().overview();

        assertThat(result.status()).isEqualTo("healthy");
        assertThat(result.checkedAt()).isEqualTo(NOW);
        assertThat(result.applicationName()).isEqualTo("nhs-test");
        assertThat(result.runtime().availableProcessors()).isPositive();
        assertThat(result.runtime().heapUsedBytes()).isNotNegative();
        assertThat(result.components()).extracting(SystemHealthComponentView::key)
            .containsExactly("application", "database", "redis", "agentRuntime");
        assertThat(component(result, "database").details()).containsEntry("product", "PostgreSQL");
        assertThat(component(result, "redis").details()).containsEntry("keyCount", 42L);
    }

    @Test
    void isolatesProbeFailuresAndNeverLeaksUnderlyingErrorText() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("jdbc:postgresql://secret-host/private"));
        when(redis.getKeys()).thenThrow(new IllegalStateException("redis://secret-host:6379"));

        SystemHealthOverviewView result = service().overview();

        assertThat(result.status()).isEqualTo("unavailable");
        assertThat(component(result, "database").message()).isEqualTo("数据库连接失败");
        assertThat(component(result, "redis").message()).isEqualTo("Redis 连接失败");
        assertThat(result.toString()).doesNotContain("secret-host", "private", "6379");
    }

    @Test
    void disabledAgentRuntimeDegradesOtherwiseHealthyDeployment() {
        when(runtimeProvider.getIfAvailable()).thenReturn(null);

        SystemHealthOverviewView result = service().overview();

        assertThat(result.status()).isEqualTo("degraded");
        assertThat(component(result, "agentRuntime").status()).isEqualTo("disabled");
    }

    @Test
    void rejectsNonAdministratorsBeforeRunningAnyProbe() {
        when(principalProvider.currentPrincipal()).thenReturn(new CurrentPrincipal(
            2L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        ));

        assertThatThrownBy(() -> service().overview())
            .isInstanceOf(ServiceException.class)
            .extracting(exception -> ((ServiceException) exception).getCode())
            .isEqualTo(403);
        verifyNoInteractions(dataSourceProvider, redisProvider, runtimeProvider);
    }

    private SystemHealthApplicationService service() {
        return new SystemHealthApplicationService(
            principalProvider,
            dataSourceProvider,
            redisProvider,
            runtimeProvider,
            "nhs-test",
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private SystemHealthComponentView component(SystemHealthOverviewView view, String key) {
        return view.components().stream().filter(item -> key.equals(item.key())).findFirst().orElseThrow();
    }
}
