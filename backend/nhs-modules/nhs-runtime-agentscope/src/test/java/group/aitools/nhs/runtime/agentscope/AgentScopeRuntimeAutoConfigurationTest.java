package group.aitools.nhs.runtime.agentscope;

import group.aitools.nhs.runtime.agentscope.config.AgentScopeRuntimeAutoConfiguration;
import group.aitools.nhs.runtime.spi.AgentRunRequestResolver;
import group.aitools.nhs.runtime.spi.AgentRuntime;
import io.agentscope.core.state.AgentStateStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

@Tag("dev")
class AgentScopeRuntimeAutoConfigurationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void remainsDisabledUnlessExplicitlyEnabled() {
        contextRunner().run(context -> {
            assertNull(context.getStartupFailure());
            org.assertj.core.api.Assertions.assertThat(context).doesNotHaveBean(AgentRuntime.class);
        });
    }

    @Test
    void createsRuntimeOnlyWhenDurableDependenciesArePresent() {
        contextRunner()
            .withPropertyValues(
                "agent.runtime.agentscope.enabled=true",
                "agent.runtime.agentscope.workspace-root=" + temporaryDirectory.resolve("workspace")
            )
            .withBean(AgentStateStore.class, () -> mock(AgentStateStore.class))
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(AgentRunRequestResolver.class, () -> request -> RuntimeFixtures.runRequest())
            .run(context -> {
                assertNull(context.getStartupFailure());
                org.assertj.core.api.Assertions.assertThat(context).hasSingleBean(AgentRuntime.class);
                org.assertj.core.api.Assertions.assertThat(context)
                    .hasSingleBean(AgentScopeInvocationFactory.class);
            });
    }

    @Test
    void failsStartupWhenEnabledWithoutPersistedRunResolver() {
        contextRunner()
            .withPropertyValues(
                "agent.runtime.agentscope.enabled=true",
                "agent.runtime.agentscope.workspace-root=" + temporaryDirectory.resolve("workspace")
            )
            .withBean(AgentStateStore.class, () -> mock(AgentStateStore.class))
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .run(context -> assertNotNull(context.getStartupFailure()));
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(AgentScopeRuntimeAutoConfiguration.class)
        );
    }
}
