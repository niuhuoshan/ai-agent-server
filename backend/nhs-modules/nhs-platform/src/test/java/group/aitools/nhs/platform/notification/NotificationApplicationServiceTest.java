package group.aitools.nhs.platform.notification;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.notification.domain.AgentNotification;
import group.aitools.nhs.platform.notification.mapper.AgentNotificationMapper;
import group.aitools.nhs.platform.notification.persistence.row.TaskNotificationOwnerRow;
import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import group.aitools.nhs.platform.notification.service.NotificationMessage;
import group.aitools.nhs.platform.notification.service.NotificationRecipient;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@Tag("dev")
class NotificationApplicationServiceTest {

    private static final CurrentPrincipal HUMAN = new CurrentPrincipal(
        101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal MACHINE = new CurrentPrincipal(
        101L, "machine", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
    );

    private CurrentPrincipalProvider principalProvider;
    private PlatformIdGenerator idGenerator;
    private AgentNotificationMapper mapper;
    private NotificationApplicationService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        idGenerator = mock(PlatformIdGenerator.class);
        mapper = mock(AgentNotificationMapper.class);
        when(principalProvider.currentPrincipal()).thenReturn(HUMAN);
        service = new NotificationApplicationService(principalProvider, idGenerator, mapper);
    }

    @Test
    void inboxAlwaysUsesCurrentHumanUserId() {
        AgentNotification notification = notification(900L, 101L, "approval:1");
        when(mapper.selectInbox(101L, "approval", true, 1000L, 20))
            .thenReturn(List.of(notification));

        var result = service.list("approval", true, 1000L, 20);

        assertEquals(1, result.size());
        assertEquals(900L, result.getFirst().id());
        verify(mapper).selectInbox(101L, "approval", true, 1000L, 20);
    }

    @Test
    void serviceAccountCannotReadCollidingHumanInbox() {
        when(principalProvider.currentPrincipal()).thenReturn(MACHINE);

        ServiceException exception = assertThrows(
            ServiceException.class, () -> service.list(null, false, null, 50)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getCode());
        verify(mapper, never()).selectInbox(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
            any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void publisherRejectsMachineRecipientBeforePersistence() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.publish(
                new NotificationRecipient(101L, PrincipalType.SERVICE_ACCOUNT), message()
            )
        );
        verify(mapper, never()).insertNotification(any());
    }

    @Test
    void duplicateEventForSameHumanRecipientIsReplayed() {
        AgentNotification existing = notification(900L, 101L, "approval:1");
        when(mapper.selectByEventKey(101L, "approval:1")).thenReturn(existing);

        AgentNotification result = service.publish(
            new NotificationRecipient(101L, PrincipalType.HUMAN), message()
        );

        assertEquals(900L, result.getId());
        verify(mapper, never()).insertNotification(any());
    }

    @Test
    void cannotMarkAnotherUsersNotificationAsRead() {
        when(mapper.markRead(org.mockito.ArgumentMatchers.eq(900L),
            org.mockito.ArgumentMatchers.eq(101L), any(LocalDateTime.class))).thenReturn(0);

        ServiceException exception = assertThrows(ServiceException.class, () -> service.markRead(900L));

        assertEquals(HttpStatus.NOT_FOUND, exception.getCode());
        verify(mapper, never()).selectOwned(any(), any());
    }

    @Test
    void portalPageAndDeleteOperationsRemainOwnerScoped() {
        when(mapper.selectInboxPage(101L, null, false, 20, 10))
            .thenReturn(List.of(notification(900L, 101L, "system:1")));
        when(mapper.deleteOne(900L, 101L)).thenReturn(1);
        when(mapper.deleteRead(101L)).thenReturn(3);

        assertEquals(1, service.listPage(20, 10, false).size());
        assertEquals(3, service.deleteRead());
        service.deleteOne(900L);

        verify(mapper).selectInboxPage(101L, null, false, 20, 10);
        verify(mapper).deleteRead(101L);
        verify(mapper).deleteOne(900L, 101L);
    }

    @Test
    void machineOwnedTaskDoesNotCreateHumanInboxEntry() {
        TaskNotificationOwnerRow owner = new TaskNotificationOwnerRow();
        owner.setOwnerId(101L);
        owner.setOwnerPrincipalType("service_account");
        when(mapper.selectTaskOwner(10L)).thenReturn(owner);

        service.publishTaskOwner(10L, message());

        verify(mapper, never()).insertNotification(any());
    }

    @Test
    void approvalAudienceCreatesOneIdempotentEntryPerActiveHumanUser() {
        when(mapper.selectApprovalRecipientIds()).thenReturn(List.of(101L, 102L));
        when(idGenerator.nextId()).thenReturn(900L, 901L);
        when(mapper.insertNotification(any())).thenReturn(1);

        service.publishApprovalAudience(message());

        verify(mapper, times(2)).insertNotification(any());
        verify(mapper).selectByEventKey(101L, "approval:1");
        verify(mapper).selectByEventKey(102L, "approval:1");
    }

    private NotificationMessage message() {
        return new NotificationMessage(
            "approval:1", "approval", "warning", "待审批", "任务需要审批", "approval", 1L
        );
    }

    private AgentNotification notification(Long id, Long userId, String eventKey) {
        AgentNotification value = new AgentNotification();
        value.setId(id);
        value.setUserId(userId);
        value.setEventKey(eventKey);
        value.setCategory("approval");
        value.setLevel("warning");
        value.setTitle("待审批");
        value.setCreatedAt(LocalDateTime.now());
        return value;
    }
}
