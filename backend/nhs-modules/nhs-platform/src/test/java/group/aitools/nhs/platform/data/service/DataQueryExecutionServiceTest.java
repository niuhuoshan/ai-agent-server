package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.web.DataQueryRequest;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Security-focused tests for governed query validation and execution. */
@Tag("dev")
class DataQueryExecutionServiceTest {

    @Test
    void rowPolicyEnabledDatasetFailsClosedBeforeSqlValidationOrExecution() {
        CurrentPrincipal principal = new CurrentPrincipal(
            101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        );
        DataSourceCatalogService catalog = mock(DataSourceCatalogService.class);
        AgentDataDataset dataset = new AgentDataDataset();
        dataset.setId(11L);
        dataset.setDataSourceId(21L);
        dataset.setDatasetKey("sales");
        dataset.setStatus("active");
        dataset.setEnableRowPolicy(true);
        dataset.setOwnerId(101L);
        AgentDataSource source = new AgentDataSource();
        source.setId(21L);
        source.setStatus("active");
        when(catalog.requireDataset(11L)).thenReturn(dataset);
        when(catalog.requireSource(21L)).thenReturn(source);

        AuthorizationEnforcer enforcer = mock(AuthorizationEnforcer.class);
        when(enforcer.requireAllowed(any(), any())).thenReturn(new AuthorizationDecision(
            PermissionEffect.ALLOW, "TEST_ALLOW", "test", List.of()
        ));
        DataCatalogMapper mapper = mock(DataCatalogMapper.class);
        ReadOnlySqlValidator validator = mock(ReadOnlySqlValidator.class);
        ReadOnlyJdbcConnectionFactory connections = mock(ReadOnlyJdbcConnectionFactory.class);
        DataQueryExecutionService service = new DataQueryExecutionService(
            (CurrentPrincipalProvider) () -> principal,
            enforcer,
            mock(TaskQueryService.class),
            mock(PlatformIdGenerator.class),
            mapper,
            catalog,
            validator,
            connections,
            mock(JsonMapper.class)
        );
        DataQueryRequest request = new DataQueryRequest(
            11L, null, null, null, "sales", "SELECT id FROM orders"
        );

        assertThatThrownBy(() -> service.validate(request))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("行级权限")
            .extracting(exception -> ((ServiceException) exception).getCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> service.execute(request))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("行级权限");
        DataQueryRequest sessionRequest = new DataQueryRequest(
            11L, null, null, 77L, "sales", "SELECT id FROM orders"
        );
        assertThatThrownBy(() -> service.executeSessionRuntime(
            principal, sessionRequest, "trace-session"
        )).isInstanceOf(ServiceException.class).hasMessageContaining("行级权限");

        verify(enforcer, times(3)).requireAllowed(any(), any());
        verifyNoInteractions(mapper, validator, connections);
    }

    @Test
    void sessionRuntimeRejectsTaskOrRunIdentityBeforeAuthorization() {
        DataQueryExecutionService service = new DataQueryExecutionService(
            mock(CurrentPrincipalProvider.class), mock(AuthorizationEnforcer.class),
            mock(TaskQueryService.class), mock(PlatformIdGenerator.class),
            mock(DataCatalogMapper.class), mock(DataSourceCatalogService.class),
            mock(ReadOnlySqlValidator.class), mock(ReadOnlyJdbcConnectionFactory.class),
            mock(JsonMapper.class)
        );
        CurrentPrincipal principal = new CurrentPrincipal(
            101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        );

        assertThatThrownBy(() -> service.executeSessionRuntime(
            principal,
            new DataQueryRequest(11L, 12L, 13L, 77L, "sales", "SELECT id FROM orders"),
            "trace-session"
        )).isInstanceOf(SecurityException.class).hasMessageContaining("不能绑定任务运行记录");
    }
}
