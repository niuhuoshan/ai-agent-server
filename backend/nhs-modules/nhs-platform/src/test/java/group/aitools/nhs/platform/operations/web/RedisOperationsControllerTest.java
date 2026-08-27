package group.aitools.nhs.platform.operations.web;

import group.aitools.nhs.platform.operations.service.RedisOperationsApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("dev")
class RedisOperationsControllerTest {

    private RedisOperationsApplicationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(RedisOperationsApplicationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RedisOperationsController(service)).build();
    }

    @Test
    void exposesStructuredPlatformKeyScan() throws Exception {
        when(service.list("cache:*")).thenReturn(new RedisKeyListView(
            3, 1, false, "cache:*", List.of(new RedisKeyView("cache:a", "string", 60))
        ));

        mockMvc.perform(get("/platform/operations/redis/keys").param("pattern", "cache:*")).andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.totalCount").value(3))
            .andExpect(jsonPath("$.data.keys[0].name").value("cache:a"));
        verify(service).list("cache:*");
    }

    @Test
    void preservesNhsRedisBrowserPath() throws Exception {
        when(service.list("session:*")).thenReturn(new RedisKeyListView(
            1, 1, false, "session:*", List.of(new RedisKeyView("session:1", "hash", -1))
        ));

        mockMvc.perform(get("/api/portal/system/redis/keys-list").param("pattern", "session:*"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.keys[0].type").value("hash"));
        verify(service).list("session:*");
    }

    @Test
    void bindsExplicitConfirmationForSelectiveFlush() throws Exception {
        when(service.flush(new RedisFlushRequest(true, true))).thenReturn(
            new RedisMutationView("success", 2, 1, "已清理")
        );

        mockMvc.perform(post("/platform/operations/redis/flush")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true,\"preserveConversations\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.affectedCount").value(2))
            .andExpect(jsonPath("$.data.preservedCount").value(1));
        verify(service).flush(new RedisFlushRequest(true, true));
    }
}
