package group.aitools.nhs.platform.sandbox;

import group.aitools.nhs.platform.sandbox.service.SandboxPolicyValidator;
import group.aitools.nhs.platform.sandbox.service.SandboxPolicyValidator.ValidatedPolicy;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class SandboxPolicyValidatorTest {

    private final SandboxPolicyValidator validator = new SandboxPolicyValidator();

    @Test
    void shellMetacharactersRemainLiteralArgvWithoutStringConcatenation() {
        List<String> argv = List.of(
            "python", "-c", "print('safe')", ";rm -rf /", "$(id)", "`id`", "\"quoted\""
        );

        ValidatedPolicy result = valid(argv, ".", "none", List.of());

        assertEquals(argv, result.argv());
    }

    @Test
    void rejectsControlCharactersAndWorkspaceTraversal() {
        assertThrows(ServiceException.class, () -> valid(List.of("python", "bad\narg"), ".", "none", List.of()));
        assertThrows(ServiceException.class, () -> valid(List.of("python", "bad\0arg"), ".", "none", List.of()));
        assertThrows(ServiceException.class, () -> valid(List.of("python"), "../secret", "none", List.of()));
        assertThrows(ServiceException.class, () -> valid(List.of("python"), "/etc", "none", List.of()));
        assertThrows(ServiceException.class, () -> valid(List.of("python"), "C:\\temp", "none", List.of()));
    }

    @Test
    void networkDefaultsCannotBeBypassedWithInternalHostnames() {
        assertThrows(ServiceException.class, () -> valid(
            List.of("python"), ".", "none", List.of("example.com")
        ));
        assertThrows(ServiceException.class, () -> valid(
            List.of("python"), ".", "allowlist", List.of("localhost")
        ));
        assertThrows(ServiceException.class, () -> valid(
            List.of("python"), ".", "allowlist", List.of()
        ));
    }

    private ValidatedPolicy valid(
        List<String> argv,
        String workspace,
        String network,
        List<String> hosts
    ) {
        return validator.validate(
            "python-3.11", argv, workspace, "read_write", network, hosts,
            300, 512, 1000, 128, 1048576, 0
        );
    }
}
