package group.aitools.nhs.platform.knowledge;

import group.aitools.nhs.platform.knowledge.service.KnowledgeFileStorage;
import group.aitools.nhs.platform.knowledge.service.LocalKnowledgeFileStorage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class LocalKnowledgeFileStorageTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesHashesReadsAndDeletesOpaqueDocumentReference() throws Exception {
        LocalKnowledgeFileStorage storage = new LocalKnowledgeFileStorage(temporaryDirectory);
        byte[] bytes = "approved operating procedure".getBytes(StandardCharsets.UTF_8);

        KnowledgeFileStorage.StoredFile stored = storage.put(
            1001L, new ByteArrayInputStream(bytes), bytes.length
        );

        assertEquals("1001/source.bin", stored.storageRef());
        assertEquals(bytes.length, stored.sizeBytes());
        assertEquals(64, stored.sha256().length());
        try (var input = storage.open(stored.storageRef())) {
            assertEquals("approved operating procedure", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }

        storage.delete(stored.storageRef());
        assertFalse(Files.exists(temporaryDirectory.resolve("1001")));
    }

    @Test
    void rejectsTraversalAndRemovesPartialUploadOnSizeMismatch() {
        LocalKnowledgeFileStorage storage = new LocalKnowledgeFileStorage(temporaryDirectory);

        assertThrows(SecurityException.class, () -> storage.open("../secret"));
        assertThrows(
            IllegalStateException.class,
            () -> storage.put(1002L, new ByteArrayInputStream(new byte[]{1, 2}), 3)
        );
        assertFalse(Files.exists(temporaryDirectory.resolve("1002/source.uploading")));
    }
}
