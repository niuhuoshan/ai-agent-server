package group.aitools.nhs.platform.nhs.service;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.nhs.web.PortalPrefsContracts.Preferences;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class PortalPrefsApplicationServiceTest {

    private static final CurrentPrincipal HUMAN = new CurrentPrincipal(
        7L, "alice", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );

    private CurrentPrincipalProvider principalProvider;
    private ObjectProvider<RedissonClient> redisProvider;
    private RedissonClient redisClient;
    private RBucket<String> bucket;
    private PortalPrefsApplicationService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        redisProvider = mock(ObjectProvider.class);
        redisClient = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        when(principalProvider.currentPrincipal()).thenReturn(HUMAN);
        when(redisProvider.getIfAvailable()).thenReturn(redisClient);
        when(redisClient.<String>getBucket(PortalPrefsApplicationService.KEY_PREFIX + HUMAN.id()))
            .thenReturn(bucket);
        service = new PortalPrefsApplicationService(
            principalProvider, redisProvider, JsonMapper.builder().build()
        );
    }

    @Test
    void updateNormalizesNhsPreferencePayloadBeforePersisting() {
        Preferences result = service.update(new Preferences(
            List.of(" orders ", "orders", "", "customers"),
            List.of(" b ", "b", "a"),
            List.of(" expanded ", "expanded"),
            Map.of(" revenue ", 2, "ignored", 0, "bad", -1),
            List.of(" kb-1 ", "kb-1"),
            "  compact  "
        ));

        assertThat(result.pinnedGroupIds()).containsExactly("orders", "customers");
        assertThat(result.cardOrder()).containsExactly("b", "a");
        assertThat(result.expandedGroupIds()).containsExactly("expanded");
        assertThat(result.questionClicks()).containsEntry("revenue", 2).doesNotContainKeys("ignored", "bad");
        assertThat(result.pinnedKbDatasetIds()).containsExactly("kb-1");
        assertThat(result.markdownTheme()).isEqualTo("compact");
        verify(bucket).set(anyString());
    }

    @Test
    void malformedStoredJsonFallsBackToEmptyPreferences() {
        when(bucket.get()).thenReturn("{not-json");

        assertThat(service.get()).isEqualTo(Preferences.empty());
    }

    @Test
    void missingRedisIsReportedAsServiceUnavailable() {
        when(redisProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(service::get)
            .isInstanceOf(ServiceException.class)
            .extracting(exception -> ((ServiceException) exception).getCode())
            .isEqualTo(503);
    }

    @Test
    void serviceAccountsCannotReadHumanPortalPreferences() {
        when(principalProvider.currentPrincipal()).thenReturn(new CurrentPrincipal(
            7L, "machine", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
        ));

        assertThatThrownBy(service::get)
            .isInstanceOf(ServiceException.class)
            .extracting(exception -> ((ServiceException) exception).getCode())
            .isEqualTo(403);
    }
}
