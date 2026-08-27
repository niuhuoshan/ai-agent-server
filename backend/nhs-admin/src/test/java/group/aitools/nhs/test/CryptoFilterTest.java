package group.aitools.nhs.test;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.asymmetric.RSA;
import jakarta.servlet.FilterChain;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import group.aitools.nhs.common.encrypt.filter.CryptoFilter;
import group.aitools.nhs.common.encrypt.properties.ApiDecryptProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class CryptoFilterTest {

    @Test
    void malformedEncryptedKeyIsRejectedWithoutInvokingApplication() throws Exception {
        RSA rsa = SecureUtil.rsa();
        ApiDecryptProperties properties = new ApiDecryptProperties();
        properties.setHeaderFlag("encrypt-key");
        properties.setPublicKey(rsa.getPublicKeyBase64());
        properties.setPrivateKey(rsa.getPrivateKeyBase64());
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
        FilterChain chain = mock(FilterChain.class);
        when(mapping.getHandler(any())).thenReturn(null);
        CryptoFilter filter = new CryptoFilter(properties, mapping, resolver);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.addHeader("encrypt-key", "not-valid-rsa-ciphertext");
        request.setContent("not-valid-aes-ciphertext".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(resolver).resolveException(
            eq(request), eq(response), eq(null),
            org.mockito.ArgumentMatchers.argThat(exception -> {
                if (!(exception instanceof ServiceException serviceException)) {
                    return false;
                }
                assertEquals(HttpStatus.BAD_REQUEST, serviceException.getCode());
                assertEquals("加密请求格式无效", serviceException.getMessage());
                return true;
            })
        );
    }
}
