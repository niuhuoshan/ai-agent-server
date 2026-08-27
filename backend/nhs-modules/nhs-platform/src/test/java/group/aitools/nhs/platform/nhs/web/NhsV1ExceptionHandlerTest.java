package group.aitools.nhs.platform.nhs.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.domain.R;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("dev")
class NhsV1ExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new FailureController())
        .setControllerAdvice(
            new NhsV1ExceptionHandler(),
            new NhsV1ResponseBodyAdvice(Clock.fixed(
                Instant.parse("2026-08-16T10:00:00Z"), ZoneOffset.UTC
            ))
        )
        .build();

    @Test
    void writesStandardResponseForSuccessfulV1JsonResponses() throws Exception {
        mockMvc.perform(get("/api/v1/test/success"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("success"))
            .andExpect(jsonPath("$.data.value").value(1))
            .andExpect(jsonPath("$.timestamp").value("2026-08-16T10:00:00"))
            .andExpect(jsonPath("$.trace_id").isEmpty())
            .andExpect(jsonPath("$.execution_mode").isEmpty())
            .andExpect(jsonPath("$.msg").doesNotExist());
    }

    @Test
    void writesServiceExceptionCodeToHttpStatusAndBusinessEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/test/missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.message").value("执行链路不存在"));
    }

    @Test
    void writesAuthenticationAndAuthorizationFailuresToTransportStatus() throws Exception {
        mockMvc.perform(get("/api/v1/test/not-login"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/api/v1/test/forbidden"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void writesArgumentBindingFailureToHttp400() throws Exception {
        mockMvc.perform(get("/api/v1/test/positive/not-a-number"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Validated
    @RestController
    @RequestMapping("/api/v1/test")
    static class FailureController {

        @GetMapping("/success")
        R<Map<String, Integer>> success() {
            return R.ok(Map.of("value", 1));
        }

        @GetMapping("/missing")
        void missing() {
            throw new ServiceException("执行链路不存在", HttpStatus.NOT_FOUND);
        }

        @GetMapping("/not-login")
        void notLogin() {
            throw NotLoginException.newInstance("default-login", "-1", NotLoginException.NOT_TOKEN, null);
        }

        @GetMapping("/forbidden")
        void forbidden() {
            throw new NotPermissionException("agent:trace:view");
        }

        @GetMapping("/positive/{id}")
        long positive(@PathVariable @Positive long id) {
            return id;
        }
    }
}
