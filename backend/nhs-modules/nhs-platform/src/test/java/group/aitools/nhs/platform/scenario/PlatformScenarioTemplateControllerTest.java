package group.aitools.nhs.platform.scenario.web;

import group.aitools.nhs.platform.scenario.service.ScenarioTemplateApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@Tag("dev")
class PlatformScenarioTemplateControllerTest {

    private final ScenarioTemplateApplicationService service = mock(ScenarioTemplateApplicationService.class);
    private final PlatformScenarioTemplateController controller = new PlatformScenarioTemplateController(service);

    @Test
    void exposesBothActionAndDeleteAliasesForUninstall() {
        ScenarioTemplateUninstallRequest request = new ScenarioTemplateUninstallRequest(true, "业务下线", "idem-1");

        controller.uninstall(42L, request);
        controller.deleteInstance(43L, request);

        verify(service).uninstall(42L, request);
        verify(service).uninstall(43L, request);
    }
}
