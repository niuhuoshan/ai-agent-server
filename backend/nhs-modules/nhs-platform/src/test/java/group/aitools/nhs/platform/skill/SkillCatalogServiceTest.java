package group.aitools.nhs.platform.skill;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.connector.service.ConnectorConfigurationValidator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.project.mapper.AgentProjectMapper;
import group.aitools.nhs.platform.skill.domain.AgentSkill;
import group.aitools.nhs.platform.skill.domain.AgentSkillFile;
import group.aitools.nhs.platform.skill.domain.AgentSkillVersion;
import group.aitools.nhs.platform.skill.mapper.SkillCatalogMapper;
import group.aitools.nhs.platform.skill.mapper.SkillFileMapper;
import group.aitools.nhs.platform.skill.service.SkillCatalogService;
import group.aitools.nhs.platform.skill.web.CreateSkillRequest;
import group.aitools.nhs.platform.skill.web.CreateSkillVersionRequest;
import group.aitools.nhs.platform.skill.web.SkillVersionView;
import group.aitools.nhs.platform.skill.web.UpdateSkillStatusRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class SkillCatalogServiceTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );

    private AuthorizationEnforcer authorization;
    private PlatformIdGenerator ids;
    private SkillCatalogMapper mapper;
    private SkillFileMapper fileMapper;
    private ConnectorConfigurationValidator validator;
    private SkillCatalogService service;

    @BeforeEach
    void setUp() {
        CurrentPrincipalProvider principals = mock(CurrentPrincipalProvider.class);
        authorization = mock(AuthorizationEnforcer.class);
        ids = mock(PlatformIdGenerator.class);
        mapper = mock(SkillCatalogMapper.class);
        fileMapper = mock(SkillFileMapper.class);
        validator = mock(ConnectorConfigurationValidator.class);
        when(principals.currentPrincipal()).thenReturn(MEMBER);
        when(validator.document(any(), anySet(), any())).thenAnswer(call -> call.getArgument(0));
        when(validator.boundedJson(any(), any())).thenReturn("{}");
        service = new SkillCatalogService(
            principals, authorization, ids, mapper, fileMapper, mock(AgentProjectMapper.class),
            validator, JsonMapper.builder().build()
        );
    }

    @Test
    void personalSkillCannotBeCreatedForAnotherUser() {
        CreateSkillRequest request = new CreateSkillRequest(
            "private.review", "review", null, "user", 202L,
            "Check the document.", Map.of(), Map.of()
        );

        ServiceException exception = assertThrows(
            ServiceException.class, () -> service.create(request)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getCode());
        verify(authorization, never()).requireAllowed(any(), any());
        verify(mapper, never()).insertSkill(any());
    }

    @Test
    void createdVersionHashCoversContentManifestAndRuntimeRequirements() {
        when(ids.nextId()).thenReturn(10L, 11L);

        service.create(new CreateSkillRequest(
            "finance.review", "Finance review", null, "system", null,
            "Check totals.", Map.of("summary", "review"), Map.of("maxContextBytes", 4096)
        ));

        ArgumentCaptor<AgentSkillVersion> version = ArgumentCaptor.forClass(AgentSkillVersion.class);
        verify(mapper).insertVersion(version.capture());
        assertEquals(
            ContentHashing.sha256("Check totals.\n{}\n{}"),
            version.getValue().getContentHash()
        );
    }

    @Test
    void tamperedDraftCannotBePublished() {
        AgentSkill skill = skill(3L, "draft");
        AgentSkillVersion version = version("tampered".repeat(8));
        when(mapper.selectSkill(10L)).thenReturn(skill);
        when(mapper.selectVersion(10L, 11L)).thenReturn(version);

        ServiceException exception = assertThrows(
            ServiceException.class, () -> service.publish(10L, 11L, 3L)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        verify(mapper, never()).publishDraft(anyLong(), anyLong(), any());
    }

    @Test
    void staleRevisionCannotPublishEvenWithValidContentHash() {
        AgentSkill skill = skill(4L, "draft");
        AgentSkillVersion version = version(validHash());
        when(mapper.selectSkill(10L)).thenReturn(skill);
        when(mapper.selectVersion(10L, 11L)).thenReturn(version);

        ServiceException exception = assertThrows(
            ServiceException.class, () -> service.publish(10L, 11L, 3L)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        verify(mapper, never()).publishDraft(anyLong(), anyLong(), any());
    }

    @Test
    void alreadyPublishedVersionReplaysWithoutSecondMutation() {
        AgentSkill skill = skill(4L, "active");
        AgentSkillVersion version = version(validHash());
        version.setStatus("published");
        when(mapper.selectSkill(10L)).thenReturn(skill);
        when(mapper.selectVersion(10L, 11L)).thenReturn(version);

        var result = service.publish(10L, 11L, 999L);

        assertEquals("published", result.status());
        verify(mapper, never()).publishDraft(anyLong(), anyLong(), any());
        verify(mapper, never()).updateSkillStatus(anyLong(), anyLong(), any(), anyLong(), any());
    }

    @Test
    void concurrentVersionCreationFailsWhenRootRevisionWasLost() {
        AgentSkill skill = skill(3L, "draft");
        when(mapper.selectSkill(10L)).thenReturn(skill);
        when(mapper.selectNextVersionNo(10L)).thenReturn(2);
        when(ids.nextId()).thenReturn(12L);
        when(mapper.touchSkill(eq(10L), eq(3L), eq(101L), any())).thenReturn(0);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.createVersion(10L, new CreateSkillVersionRequest(
                "New content", Map.of(), Map.of(), 3L
            ))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        verify(mapper, never()).insertVersion(any());
    }

    @Test
    void cloneCopiesVersionMetadataAndCompleteBundleIntoDraft() {
        AgentSkill skill = skill(3L, "active");
        AgentSkillVersion source = version(validHash());
        source.setStatus("published");
        AgentSkillFile sourceFile = new AgentSkillFile();
        sourceFile.setSkillId(10L);
        sourceFile.setVersionId(11L);
        sourceFile.setPath("SKILL.md");
        sourceFile.setFileKind("file");
        sourceFile.setContent("Check totals.");
        sourceFile.setContentHash(ContentHashing.sha256("Check totals."));
        sourceFile.setContentEncoding("utf8");
        sourceFile.setSizeBytes(14);
        when(mapper.selectSkill(10L)).thenReturn(skill);
        when(mapper.selectVersion(10L, 11L)).thenReturn(source);
        when(mapper.selectNextVersionNo(10L)).thenReturn(2);
        when(mapper.touchSkill(eq(10L), eq(3L), eq(101L), any())).thenReturn(1);
        when(fileMapper.selectFiles(10L, 11L)).thenReturn(List.of(sourceFile));
        when(ids.nextId()).thenReturn(12L, 13L);

        SkillVersionView result = service.cloneVersion(10L, 11L, 3L);

        assertEquals("draft", result.status());
        assertEquals("Check totals.", result.content());
        ArgumentCaptor<AgentSkillVersion> version = ArgumentCaptor.forClass(AgentSkillVersion.class);
        verify(mapper).insertVersion(version.capture());
        assertEquals(2, version.getValue().getVersionNo());
        verify(fileMapper).upsert(any(AgentSkillFile.class));
    }

    @Test
    void cloneRejectsTamperedSourceVersion() {
        AgentSkill skill = skill(3L, "active");
        when(mapper.selectSkill(10L)).thenReturn(skill);
        when(mapper.selectVersion(10L, 11L)).thenReturn(version("bad-hash"));

        ServiceException exception = assertThrows(
            ServiceException.class, () -> service.cloneVersion(10L, 11L, 3L)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        verify(mapper, never()).insertVersion(any());
    }

    @Test
    void deletingSkillProtectsPublishedVersionsAndLiveReferences() {
        AgentSkill skill = skill(3L, "draft");
        when(mapper.selectSkill(10L)).thenReturn(skill);
        when(mapper.countPublishedVersions(10L)).thenReturn(1);
        ServiceException published = assertThrows(
            ServiceException.class, () -> service.delete(10L, 3L)
        );
        assertEquals(HttpStatus.CONFLICT, published.getCode());

        when(mapper.countPublishedVersions(10L)).thenReturn(0);
        when(mapper.countActiveReferences(10L)).thenReturn(1);
        ServiceException referenced = assertThrows(
            ServiceException.class, () -> service.delete(10L, 3L)
        );
        assertEquals(HttpStatus.CONFLICT, referenced.getCode());
        verify(fileMapper, never()).deleteSkillFiles(anyLong());
    }

    @Test
    void statusEnableRequiresPublishedVersion() {
        AgentSkill skill = skill(3L, "disabled");
        when(mapper.selectSkill(10L)).thenReturn(skill);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.updateStatus(10L, new UpdateSkillStatusRequest("disabled", "active", 3L))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        verify(mapper, never()).updateSkillStatus(anyLong(), anyLong(), any(), anyLong(), any());
    }

    @Test
    void draftDeletionRemovesBundleButPublishedVersionIsProtected() {
        AgentSkill skill = skill(3L, "draft");
        AgentSkillVersion draft = version(validHash());
        when(mapper.selectSkill(10L)).thenReturn(skill);
        when(mapper.selectVersion(10L, 11L)).thenReturn(draft);
        when(mapper.touchSkill(eq(10L), eq(3L), eq(101L), any())).thenReturn(1);
        when(mapper.deleteDraftVersion(10L, 11L)).thenReturn(1);

        service.deleteVersion(10L, 11L, 3L);

        verify(fileMapper).deleteVersionFiles(10L, 11L);
        verify(mapper).deleteDraftVersion(10L, 11L);

        draft.setStatus("published");
        ServiceException published = assertThrows(
            ServiceException.class, () -> service.deleteVersion(10L, 11L, 3L)
        );
        assertEquals(HttpStatus.CONFLICT, published.getCode());
    }

    private AgentSkill skill(Long revision, String status) {
        AgentSkill skill = new AgentSkill();
        skill.setId(10L);
        skill.setSkillKey("finance.review");
        skill.setName("Finance review");
        skill.setScopeType("user");
        skill.setScopeId(101L);
        skill.setOwnerId(101L);
        skill.setRevisionNo(revision);
        skill.setStatus(status);
        return skill;
    }

    private AgentSkillVersion version(String hash) {
        AgentSkillVersion version = new AgentSkillVersion();
        version.setId(11L);
        version.setSkillId(10L);
        version.setVersionNo(1);
        version.setContent("Check totals.");
        version.setManifestJson("{}");
        version.setRuntimeRequirementsJson("{}");
        version.setContentHash(hash);
        version.setStatus("draft");
        return version;
    }

    private String validHash() {
        return ContentHashing.sha256("Check totals.\n{}\n{}");
    }
}
