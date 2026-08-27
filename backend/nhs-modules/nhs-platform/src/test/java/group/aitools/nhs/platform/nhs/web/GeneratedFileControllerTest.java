package group.aitools.nhs.platform.nhs.web;

import group.aitools.nhs.platform.nhs.service.GeneratedFileService;
import group.aitools.nhs.platform.nhs.service.NhsV1OperationAuditService;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class GeneratedFileControllerTest {

    @TempDir
    Path temporaryRoot;

    @Test
    void streamsAValidCapabilityAsAnAttachment() throws Exception {
        Path source = temporaryRoot.resolve("report.csv");
        Files.writeString(source, "name,value\nA,1\n");
        GeneratedFileService service = new GeneratedFileService(
            JsonMapper.builder().build(), temporaryRoot.resolve("published").toString()
        );
        GeneratedFileService.PublishedFile published = service.publish(source, "report.csv");
        NhsV1OperationAuditService auditService = mock(NhsV1OperationAuditService.class);
        when(auditService.fingerprint(published.artifactId())).thenReturn("artifact-fp");

        ResponseEntity<Resource> response = new GeneratedFileController(service, auditService).download(
            published.artifactId(), published.token()
        );

        assertEquals(200, response.getStatusCode().value());
        assertEquals(published.size(), response.getHeaders().getContentLength());
        assertTrue(response.getHeaders().getContentDisposition().isAttachment());
        assertEquals("report.csv", response.getHeaders().getContentDisposition().getFilename());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertEquals(
            "name,value\nA,1\n",
            new String(response.getBody().getInputStream().readAllBytes(), StandardCharsets.UTF_8)
        );
        verify(auditService).recordApplication(
            eq("generated_file.download"), eq("generated_file"), isNull(), eq("success"),
            eq("capability_accepted"), contains("artifactFingerprint=artifact-fp")
        );
    }

    @Test
    void hidesMalformedAndInvalidCapabilitiesBehindNotFound() {
        GeneratedFileService service = new GeneratedFileService(
            JsonMapper.builder().build(), temporaryRoot.resolve("published").toString()
        );
        NhsV1OperationAuditService auditService = mock(NhsV1OperationAuditService.class);
        when(auditService.fingerprint("../../etc/passwd")).thenReturn("invalid-fp");
        GeneratedFileController controller = new GeneratedFileController(service, auditService);

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> controller.download("../../etc/passwd", "wrong")
        );
        assertEquals(404, exception.getStatusCode().value());
        verify(auditService).recordApplication(
            "generated_file.download", "generated_file", null, "deny",
            "capability_invalid_or_expired", "artifactFingerprint=invalid-fp"
        );
    }

    @Test
    void doesNotStreamAValidFileWhenSuccessAuditCannotBePersisted() throws Exception {
        Path source = temporaryRoot.resolve("report.csv");
        Files.writeString(source, "name,value\nA,1\n");
        GeneratedFileService service = new GeneratedFileService(
            JsonMapper.builder().build(), temporaryRoot.resolve("published").toString()
        );
        GeneratedFileService.PublishedFile published = service.publish(source, "report.csv");
        NhsV1OperationAuditService auditService = mock(NhsV1OperationAuditService.class);
        when(auditService.fingerprint(published.artifactId())).thenReturn("artifact-fp");
        ServiceException auditFailure = new ServiceException("操作审计写入失败，请稍后重试", 503);
        doThrow(auditFailure).when(auditService).recordApplication(
            eq("generated_file.download"), eq("generated_file"), isNull(), eq("success"),
            eq("capability_accepted"), contains("artifactFingerprint=artifact-fp")
        );

        ServiceException thrown = assertThrows(ServiceException.class, () ->
            new GeneratedFileController(service, auditService).download(
                published.artifactId(), published.token()
            )
        );

        assertEquals(auditFailure, thrown);
    }

    @Test
    void preservesCapabilityDenialWhenDenyAuditCannotBePersisted() {
        GeneratedFileService service = new GeneratedFileService(
            JsonMapper.builder().build(), temporaryRoot.resolve("published").toString()
        );
        NhsV1OperationAuditService auditService = mock(NhsV1OperationAuditService.class);
        when(auditService.fingerprint("missing")).thenReturn("missing-fp");
        ServiceException auditFailure = new ServiceException("操作审计写入失败，请稍后重试", 503);
        doThrow(auditFailure).when(auditService).recordApplication(
            "generated_file.download", "generated_file", null, "deny",
            "capability_invalid_or_expired", "artifactFingerprint=missing-fp"
        );

        ServiceException thrown = assertThrows(ServiceException.class, () ->
            new GeneratedFileController(service, auditService).download("missing", "wrong")
        );

        assertEquals(auditFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0] instanceof ResponseStatusException);
        assertEquals(
            404, ((ResponseStatusException) thrown.getSuppressed()[0]).getStatusCode().value()
        );
    }
}
