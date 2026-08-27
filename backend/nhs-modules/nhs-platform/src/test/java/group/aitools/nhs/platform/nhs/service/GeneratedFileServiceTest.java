package group.aitools.nhs.platform.nhs.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class GeneratedFileServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @TempDir
    Path temporaryRoot;

    @Test
    void publishesHashedCapabilityAndResolvesTheCopiedFile() throws Exception {
        Path source = temporaryRoot.resolve("source.xlsx");
        Files.writeString(source, "workbook-content");
        Path publishedRoot = temporaryRoot.resolve("published");
        GeneratedFileService service = service(publishedRoot, NOW);

        GeneratedFileService.PublishedFile published = service.publish(source, "reports/quarter.xlsx");

        assertEquals(32, published.artifactId().length());
        assertTrue(published.downloadUrl().startsWith("/api/v1/chat/generated-files/"));
        assertEquals("quarter.xlsx", published.fileName());
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            published.mimeType()
        );
        String manifest = Files.readString(
            publishedRoot.resolve(published.artifactId()).resolve("manifest.json")
        );
        assertFalse(manifest.contains(published.token()));
        assertTrue(service.resolve(published.artifactId(), "wrong-token").isEmpty());
        GeneratedFileService.GeneratedFile resolved = service.resolve(
            published.artifactId(), published.token()
        ).orElseThrow();
        assertEquals("workbook-content", Files.readString(resolved.path()));
    }

    @Test
    void expiredCapabilitiesAreRemovedLazily() throws Exception {
        Path source = temporaryRoot.resolve("letter.docx");
        Files.writeString(source, "letter");
        Path publishedRoot = temporaryRoot.resolve("published");
        GeneratedFileService publisher = service(publishedRoot, NOW);
        GeneratedFileService.PublishedFile published = publisher.publish(
            source, "letter.docx", Duration.ofMinutes(5)
        );

        GeneratedFileService expiredReader = service(publishedRoot, NOW.plus(Duration.ofMinutes(6)));

        assertTrue(expiredReader.resolve(published.artifactId(), published.token()).isEmpty());
        assertFalse(Files.exists(publishedRoot.resolve(published.artifactId())));
    }

    @Test
    void tamperedManifestCannotEscapeItsArtifactDirectory() throws Exception {
        Path source = temporaryRoot.resolve("report.txt");
        Files.writeString(source, "safe");
        Path publishedRoot = temporaryRoot.resolve("published");
        GeneratedFileService service = service(publishedRoot, NOW);
        GeneratedFileService.PublishedFile published = service.publish(source, "report.txt");
        Path manifest = publishedRoot.resolve(published.artifactId()).resolve("manifest.json");
        String tampered = Files.readString(manifest).replace("report.txt", "../outside.txt");
        Files.writeString(manifest, tampered);
        Files.writeString(publishedRoot.resolve("outside.txt"), "secret");

        assertTrue(service.resolve(published.artifactId(), published.token()).isEmpty());
    }

    @Test
    void purgeExpiredRemovesOldCrashStagingDirectoriesAndFiles() throws Exception {
        Path publishedRoot = temporaryRoot.resolve("published");
        Files.createDirectories(publishedRoot);
        Path oldDirectory = publishedRoot.resolve(".tmp-old-directory");
        Files.createDirectory(oldDirectory);
        Files.writeString(oldDirectory.resolve("partial.xlsx"), "partial");
        Path oldFile = publishedRoot.resolve(".tmp-old-file");
        Files.writeString(oldFile, "partial-manifest");
        FileTime oldTimestamp = FileTime.from(
            NOW.minus(GeneratedFileService.STAGING_MAX_AGE).minusSeconds(1)
        );
        Files.setLastModifiedTime(oldDirectory, oldTimestamp);
        Files.setLastModifiedTime(oldFile, oldTimestamp);

        service(publishedRoot, NOW).purgeExpired();

        assertFalse(Files.exists(oldDirectory));
        assertFalse(Files.exists(oldFile));
    }

    @Test
    void purgeExpiredRetainsRecentActiveStagingDirectory() throws Exception {
        Path publishedRoot = temporaryRoot.resolve("published");
        Files.createDirectories(publishedRoot);
        Path activeDirectory = publishedRoot.resolve(".tmp-active-directory");
        Files.createDirectory(activeDirectory);
        Path partialFile = activeDirectory.resolve("partial.xlsx");
        Files.writeString(partialFile, "still-copying");
        Files.setLastModifiedTime(
            activeDirectory,
            FileTime.from(NOW.minus(GeneratedFileService.STAGING_MAX_AGE).plusSeconds(1))
        );

        service(publishedRoot, NOW).purgeExpired();

        assertTrue(Files.isDirectory(activeDirectory));
        assertEquals("still-copying", Files.readString(partialFile));
    }

    private GeneratedFileService service(Path root, Instant now) {
        return new GeneratedFileService(
            JsonMapper.builder().build(), root, Clock.fixed(now, ZoneOffset.UTC), new SecureRandom()
        );
    }
}
