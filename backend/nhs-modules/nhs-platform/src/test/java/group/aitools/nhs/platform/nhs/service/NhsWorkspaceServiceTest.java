package group.aitools.nhs.platform.nhs.service;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class NhsWorkspaceServiceTest {

    @TempDir
    Path temporaryRoot;

    private AuthorizationEnforcer enforcer;
    private NhsWorkspaceService service;

    @BeforeEach
    void setUp() {
        CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
        when(principalProvider.currentPrincipal()).thenReturn(new CurrentPrincipal(
            101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        ));
        enforcer = mock(AuthorizationEnforcer.class);
        service = new NhsWorkspaceService(
            principalProvider, enforcer, JsonMapper.builder().build(), temporaryRoot.toString()
        );
    }

    @Test
    void deleteMovesToTrashAndRestoreKeepsContent() {
        service.createEntry("", "docs", "dir");
        service.write("docs/readme.md", "hello");

        Map<String, Object> deleted = service.delete("docs/readme.md");
        assertTrue(service.list("docs").isEmpty());
        assertEquals(1, service.trashEntries().size());

        service.restore(String.valueOf(deleted.get("trash_id")), null);
        assertEquals("hello", service.preview("docs/readme.md").get("content"));
        verify(enforcer, atLeastOnce()).requireAllowed(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void directoriesCanBeRenamedDeletedAndRestored() {
        service.createEntry("", "draft", "dir");
        service.write("draft/note.txt", "note");
        service.rename("draft", "published");

        Map<String, Object> deleted = service.delete("published");
        assertTrue(service.list("").isEmpty());
        service.restore(String.valueOf(deleted.get("trash_id")), null);

        assertEquals("note", service.preview("published/note.txt").get("content"));
    }

    @Test
    void purgeMakesTrashEntryUnrecoverable() {
        service.write("temporary.txt", "content");
        Map<String, Object> deleted = service.delete("temporary.txt");
        String trashId = String.valueOf(deleted.get("trash_id"));

        service.purge(trashId, null);

        ServiceException exception = assertThrows(
            ServiceException.class, () -> service.restore(trashId, null)
        );
        assertEquals(HttpStatus.NOT_FOUND, exception.getCode());
    }

    @Test
    void uploadSearchRecentAndPreferencesUseRealFiles() {
        MockMultipartFile upload = new MockMultipartFile(
            "file", "evidence.txt", "text/plain", "proof".getBytes()
        );
        service.upload("", upload);

        assertEquals(1, service.search("evidence", "").size());
        assertFalse(service.recent("", 10).isEmpty());
        assertEquals(Map.of("view", "list", "sort", "mtime"), service.updateBrowserPrefs(
            Map.of("view", "list", "sort", "mtime")
        ));
        assertEquals("list", service.browserPrefs().get("view"));
    }

    @Test
    void textSearchReturnsMatchingLineWithoutExposingPhysicalRoot() {
        service.write("docs/log.txt", "first\nneedle value\nlast");

        List<Map<String, Object>> matches = service.searchText("NEEDLE", "", 10);

        assertEquals(1, matches.size());
        assertEquals("docs/log.txt", matches.getFirst().get("path"));
        assertEquals(2, matches.getFirst().get("line"));
        assertEquals("needle value", matches.getFirst().get("text"));
        assertFalse(String.valueOf(matches.getFirst().get("path")).contains(temporaryRoot.toString()));
    }

    @Test
    void recentFilesAreSanitizedPersistedAndProjectedFromCurrentFiles() {
        service.write("first.txt", "one");
        service.write("second.txt", "two");

        List<Map<String, Object>> saved = service.updateRecent(List.of(
            Map.of("path", "first.txt", "name", "ignored", "mtime", 1),
            Map.of("path", "../escape.txt", "name", "escape", "mtime", 2),
            Map.of("path", "first.txt", "name", "duplicate", "mtime", 3),
            Map.of("path", "second.txt", "name", "second.txt", "mtime", 4)
        ));

        assertEquals(List.of("first.txt", "second.txt"), saved.stream().map(item -> item.get("path")).toList());
        assertEquals(List.of("first.txt", "second.txt"), service.storedRecent(20).stream()
            .map(item -> item.get("path")).toList());
        assertTrue(service.list("").stream().noneMatch(item -> ".recent-files.json".equals(item.get("name"))));
    }

    @Test
    void traversalAndHiddenInternalPathsAreRejected() {
        ServiceException traversal = assertThrows(
            ServiceException.class, () -> service.write("../escape.txt", "blocked")
        );
        assertEquals(HttpStatus.FORBIDDEN, traversal.getCode());

        ServiceException internal = assertThrows(
            ServiceException.class, () -> service.preview(".trash/index.json")
        );
        assertEquals(HttpStatus.FORBIDDEN, internal.getCode());
    }

    @Test
    void canvasWriteIsCreateOnlyUnlessOverwriteIsExplicit() {
        service.writeCanvas("report.md", "first".getBytes(), false);

        ServiceException duplicate = assertThrows(
            ServiceException.class,
            () -> service.writeCanvas("report.md", "second".getBytes(), false)
        );
        assertEquals(HttpStatus.CONFLICT, duplicate.getCode());
        assertEquals("first", service.preview("report.md").get("content"));

        service.writeCanvas("report.md", "second".getBytes(), true);
        assertEquals("second", service.preview("report.md").get("content"));
    }
}
