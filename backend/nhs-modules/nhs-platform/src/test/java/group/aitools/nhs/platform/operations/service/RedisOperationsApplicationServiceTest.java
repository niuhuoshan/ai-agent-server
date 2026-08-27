package group.aitools.nhs.platform.operations.service;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.operations.web.RedisDeleteRequest;
import group.aitools.nhs.platform.operations.web.RedisFlushRequest;
import group.aitools.nhs.platform.operations.web.RedisKeyDetailView;
import group.aitools.nhs.platform.operations.web.RedisKeyListView;
import group.aitools.nhs.platform.operations.web.RedisMutationView;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RMap;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
class RedisOperationsApplicationServiceTest {

    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER, PlatformRole.PLATFORM_ADMIN)
    );

    private CurrentPrincipalProvider principalProvider;
    private ObjectProvider<RedissonClient> redisProvider;
    private RedissonClient redis;
    private RKeys keys;
    private AgentAuditEventMapper auditMapper;
    private PlatformIdGenerator idGenerator;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        redisProvider = mock(ObjectProvider.class);
        redis = mock(RedissonClient.class);
        keys = mock(RKeys.class);
        auditMapper = mock(AgentAuditEventMapper.class);
        idGenerator = mock(PlatformIdGenerator.class);

        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        when(redis.getKeys()).thenReturn(keys);
        when(keys.getType(anyString())).thenReturn(RType.OBJECT);
        when(keys.remainTimeToLive(anyString())).thenReturn(60L);
        when(idGenerator.nextId()).thenReturn(100L, 101L, 102L, 103L);
    }

    @Test
    void scansKeysWithTypesTtlAndBoundedPattern() {
        when(keys.count()).thenReturn(3L);
        when(keys.getKeysByPattern("cache:*")).thenReturn(List.of("cache:a", "cache:b"));

        RedisKeyListView result = service().list("cache:*");

        assertThat(result.totalCount()).isEqualTo(3L);
        assertThat(result.returnedCount()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();
        assertThat(result.keys()).extracting(item -> item.name()).containsExactly("cache:a", "cache:b");
        assertThat(result.keys()).allSatisfy(item -> {
            assertThat(item.type()).isEqualTo("string");
            assertThat(item.ttlSeconds()).isEqualTo(60L);
        });
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void readsStructuredMapValueWithoutReturningRedisObjects() {
        RMap map = mock(RMap.class);
        when(keys.getType("cache:map")).thenReturn(RType.MAP);
        when(redis.getMap("cache:map")).thenReturn(map);
        when(map.readAllMap()).thenReturn(Map.of("state", "ready", "count", 2));

        RedisKeyDetailView result = service().detail("cache:map");

        assertThat(result.type()).isEqualTo("hash");
        assertThat(result.value()).isEqualTo(Map.of("state", "ready", "count", 2));
        assertThat(result.valueTruncated()).isFalse();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void redactsCredentialKeysAndSensitiveHashFields() {
        RMap map = mock(RMap.class);
        when(keys.getType("provider:config")).thenReturn(RType.MAP);
        when(redis.getMap("provider:config")).thenReturn(map);
        when(map.readAllMap()).thenReturn(Map.of("api_key", "plain-secret", "endpoint", "https://example.test"));

        RedisKeyDetailView result = service().detail("provider:config");

        assertThat(result.value()).isEqualTo(Map.of(
            "api_key", "<redacted>", "endpoint", "https://example.test"
        ));
        assertThat(result.toString()).doesNotContain("plain-secret");
    }

    @Test
    void destructiveActionsRequireExplicitConfirmation() {
        assertThatThrownBy(() -> service().delete("cache:a", false))
            .isInstanceOf(ServiceException.class)
            .extracting(exception -> ((ServiceException) exception).getCode())
            .isEqualTo(400);
        assertThatThrownBy(() -> service().deleteBatch(new RedisDeleteRequest(List.of("cache:a"), false)))
            .isInstanceOf(ServiceException.class)
            .extracting(exception -> ((ServiceException) exception).getCode())
            .isEqualTo(400);
        verify(keys, never()).delete(anyString());
    }

    @Test
    void selectiveFlushPreservesConversationKeysAndAuditsCounts() {
        when(keys.getKeysByPattern("*")).thenReturn(List.of(
            "cache:a", "conversation:1:2:history", "rate-limit:1"
        ));
        when(keys.delete("cache:a", "rate-limit:1")).thenReturn(2L);
        when(keys.count()).thenReturn(1L);

        RedisMutationView result = service().flush(new RedisFlushRequest(true, true));

        assertThat(result.affectedCount()).isEqualTo(2L);
        assertThat(result.preservedCount()).isEqualTo(1L);
        verify(keys).delete("cache:a", "rate-limit:1");
    }

    @Test
    void rejectsNonAdministratorsBeforeAccessingRedis() {
        when(principalProvider.currentPrincipal()).thenReturn(new CurrentPrincipal(
            2L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        ));

        assertThatThrownBy(() -> service().list("*"))
            .isInstanceOf(ServiceException.class)
            .extracting(exception -> ((ServiceException) exception).getCode())
            .isEqualTo(403);
        verifyNoInteractions(redisProvider);
    }

    @Test
    void reportsUnavailableWhenRedisClientIsMissing() {
        when(redisProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service().list("*"))
            .isInstanceOf(ServiceException.class)
            .extracting(exception -> ((ServiceException) exception).getCode())
            .isEqualTo(503);
    }

    private RedisOperationsApplicationService service() {
        return new RedisOperationsApplicationService(
            principalProvider, redisProvider, auditMapper, idGenerator
        );
    }
}
