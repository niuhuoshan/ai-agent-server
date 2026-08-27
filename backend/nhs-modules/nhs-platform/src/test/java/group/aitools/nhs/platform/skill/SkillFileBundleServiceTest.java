package group.aitools.nhs.platform.skill;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.skill.mapper.SkillCatalogMapper;
import group.aitools.nhs.platform.skill.mapper.SkillFileMapper;
import group.aitools.nhs.platform.skill.domain.AgentSkillFile;
import group.aitools.nhs.platform.skill.service.SkillCatalogService;
import group.aitools.nhs.platform.skill.service.SkillFileBundleService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class SkillFileBundleServiceTest {

    private final CurrentPrincipal principal = new CurrentPrincipal(
        101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private SkillFileMapper mapper;
    private SkillCatalogService catalog;
    private SkillFileBundleService service;

    @BeforeEach
    void setUp() {
        CurrentPrincipalProvider principals = mock(CurrentPrincipalProvider.class);
        when(principals.currentPrincipal()).thenReturn(principal);
        mapper = mock(SkillFileMapper.class);
        catalog = mock(SkillCatalogService.class);
        service = new SkillFileBundleService(
            principals, mock(PlatformIdGenerator.class), mapper, catalog
        );
    }

    @Test
    void archiveRejectsAbsoluteAndTraversalPaths() throws IOException {
        assertArchiveRejected("/etc/passwd");
        assertArchiveRejected("nested/../../escape.txt");
        verify(mapper, never()).upsert(any());
    }

    @Test
    void emptyArchiveIsRejected() throws IOException {
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.importArchive(10L, 11L, zip())
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        verify(mapper, never()).upsert(any());
    }

    @Test
    void writesAreDelegatedToVersionAccessPolicy() {
        ServiceException denied = new ServiceException("published", HttpStatus.CONFLICT);
        org.mockito.Mockito.doThrow(denied).when(catalog).requireFileAccess(10L, 11L, true);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.put(10L, 11L, "SKILL.md", "content")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        verify(mapper, never()).upsert(any());
    }

    @Test
    void coreSkillFileCannotBeDeleted() {
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.delete(10L, 11L, "SKILL.md")
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        verify(mapper, never()).softDeleteTree(anyLong(), anyLong(), any(), anyLong(), any());
    }

    @Test
    void binaryFilesArePersistedWithoutUtf8Loss() {
        byte[] pngHeader = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x00, (byte) 0xff};
        AgentSkillFile stored = new AgentSkillFile();
        stored.setPath("assets/logo.png");
        stored.setContentBytes(pngHeader);
        stored.setContentEncoding("binary");
        stored.setContentHash(group.aitools.nhs.platform.common.ContentHashing.sha256(pngHeader));
        stored.setSizeBytes(pngHeader.length);
        when(mapper.selectFile(10L, 11L, "assets/logo.png")).thenReturn(stored);
        when(mapper.selectFiles(10L, 11L)).thenReturn(List.of(stored));

        var result = service.putBytes(10L, 11L, "assets/logo.png", pngHeader);

        org.mockito.ArgumentCaptor<AgentSkillFile> captor = org.mockito.ArgumentCaptor.forClass(AgentSkillFile.class);
        verify(mapper).upsert(captor.capture());
        org.junit.jupiter.api.Assertions.assertArrayEquals(pngHeader, captor.getValue().getContentBytes());
        org.junit.jupiter.api.Assertions.assertNull(captor.getValue().getContent());
        org.junit.jupiter.api.Assertions.assertTrue(result.binary());
    }

    @Test
    void exportPreservesTextAndBinaryEntries() throws IOException {
        AgentSkillFile text = new AgentSkillFile();
        text.setPath("SKILL.md");
        text.setFileKind("file");
        text.setContent("# Skill");
        AgentSkillFile binary = new AgentSkillFile();
        binary.setPath("assets/logo.bin");
        binary.setFileKind("file");
        binary.setContentBytes(new byte[] {0x00, (byte) 0xff, 0x01});
        when(mapper.selectFiles(10L, 11L)).thenReturn(List.of(text, binary));

        byte[] archive = service.exportArchive(10L, 11L);
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry first = input.getNextEntry();
            org.junit.jupiter.api.Assertions.assertEquals("SKILL.md", first.getName());
            org.junit.jupiter.api.Assertions.assertEquals("# Skill", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            ZipEntry second = input.getNextEntry();
            org.junit.jupiter.api.Assertions.assertEquals("assets/logo.bin", second.getName());
            org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] {0x00, (byte) 0xff, 0x01}, input.readAllBytes());
        }
    }

    @Test
    void tarArchiveImportsBinaryEntriesAndRejectsLinks() throws IOException {
        byte[] binary = new byte[] {0x00, (byte) 0xff, 0x01};
        AgentSkillFile stored = new AgentSkillFile();
        stored.setPath("assets/logo.bin");
        stored.setFileKind("file");
        stored.setContentBytes(binary);
        stored.setContentHash(group.aitools.nhs.platform.common.ContentHashing.sha256(binary));
        stored.setSizeBytes(binary.length);
        when(mapper.selectFile(10L, 11L, "assets/logo.bin")).thenReturn(stored);
        when(mapper.selectFiles(10L, 11L)).thenReturn(List.of(stored));

        byte[] archive = tar("assets/logo.bin", binary, false);
        var imported = service.importArchive(10L, 11L, archive, null, "skill.tar");

        assertEquals(1, imported.size());
        org.mockito.ArgumentCaptor<AgentSkillFile> captor = org.mockito.ArgumentCaptor.forClass(AgentSkillFile.class);
        verify(mapper).upsert(captor.capture());
        org.junit.jupiter.api.Assertions.assertArrayEquals(binary, captor.getValue().getContentBytes());

        ServiceException rejected = assertThrows(
            ServiceException.class,
            () -> service.importArchive(10L, 11L, tar("assets/link", new byte[0], true), null, "skill.tar")
        );
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getCode());
    }

    @Test
    void tarGzipArchiveImportsEntries() throws IOException {
        byte[] content = "#!/bin/sh\nprintf ok".getBytes(StandardCharsets.UTF_8);
        AgentSkillFile stored = new AgentSkillFile();
        stored.setPath("scripts/check.sh");
        stored.setFileKind("file");
        stored.setContent(new String(content, StandardCharsets.UTF_8));
        stored.setContentEncoding("utf8");
        stored.setContentHash(group.aitools.nhs.platform.common.ContentHashing.sha256(content));
        stored.setSizeBytes(content.length);
        when(mapper.selectFile(10L, 11L, "scripts/check.sh")).thenReturn(stored);
        when(mapper.selectFiles(10L, 11L)).thenReturn(List.of(stored));

        var imported = service.importArchive(
            10L, 11L, tarGzip("scripts/check.sh", content), null, "skill.tgz"
        );

        assertEquals(1, imported.size());
        org.mockito.ArgumentCaptor<AgentSkillFile> captor = org.mockito.ArgumentCaptor.forClass(AgentSkillFile.class);
        verify(mapper).upsert(captor.capture());
        assertEquals(new String(content, StandardCharsets.UTF_8), captor.getValue().getContent());
    }

    private void assertArchiveRejected(String path) throws IOException {
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.importArchive(10L, 11L, zip(path))
        );
        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
    }

    private byte[] zip(String... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write("content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private byte[] tar(String path, byte[] content, boolean link) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry entry = link
                ? new TarArchiveEntry(path, TarArchiveEntry.LF_SYMLINK)
                : new TarArchiveEntry(path);
            if (link) {
                entry.setLinkName("SKILL.md");
            } else {
                entry.setSize(content.length);
            }
            tar.putArchiveEntry(entry);
            if (!link) tar.write(content);
            tar.closeArchiveEntry();
            tar.finish();
        }
        return bytes.toByteArray();
    }

    private byte[] tarGzip(String path, byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes)) {
            TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip);
            TarArchiveEntry entry = new TarArchiveEntry(path);
            entry.setSize(content.length);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
            tar.finish();
            tar.close();
        }
        return bytes.toByteArray();
    }
}
