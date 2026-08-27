package group.aitools.nhs.platform.notification.service;

import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class NotificationChannelSenderTest {

    private HttpClient client;
    private NotificationChannelSender sender;

    @BeforeEach
    void setUp() {
        client = mock(HttpClient.class);
        sender = new NotificationChannelSender(JsonMapper.builder().build(), client, false, false);
    }

    @Test
    void sendsWechatMarkdownToOfficialProviderHost() throws Exception {
        HttpResponse<byte[]> response = response(200, "{\"errcode\":0}");
        when(client.<byte[]>send(
            any(HttpRequest.class),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()
        )).thenReturn(response);

        sender.sendTest("wechat_work", Map.of(
            "webhook_url", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=private-key"
        ));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(
            captor.capture(),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()
        );
        HttpRequest request = captor.getValue();
        assertEquals("qyapi.weixin.qq.com", request.uri().getHost());
        assertEquals("POST", request.method());
        String body = requestBody(request);
        assertTrue(body.contains("\"msgtype\":\"markdown\""));
        assertTrue(body.contains("消息通知连通性测试"));
        assertFalse(body.contains("private-key"));
    }

    @Test
    void rejectedProviderResponseBecomes502WithoutLeakingCredentials() throws Exception {
        HttpResponse<byte[]> response = response(
            200, "{\"errcode\":310000,\"errmsg\":\"bad secret-token\"}"
        );
        when(client.<byte[]>send(
            any(HttpRequest.class),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()
        )).thenReturn(response);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> sender.sendTest("dingtalk", Map.of(
                "webhook_url", "https://oapi.dingtalk.com/robot/send?access_token=secret-token"
            ))
        );

        assertEquals(502, exception.getCode());
        assertFalse(exception.getMessage().contains("secret-token"));
        assertFalse(exception.getMessage().contains("bad"));
    }

    @Test
    void transportFailureBecomesExplicit503() throws Exception {
        when(client.<byte[]>send(
            any(HttpRequest.class),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()
        )).thenThrow(new IOException("contains-secret"));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> sender.sendTest("wechat_work", Map.of(
                "webhook_url", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=private-key"
            ))
        );

        assertEquals(503, exception.getCode());
        assertFalse(exception.getMessage().contains("contains-secret"));
    }

    @Test
    void refusesNonOfficialWebhookBeforeOpeningConnection() throws Exception {
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> sender.sendTest("dingtalk", Map.of(
                "webhook_url", "https://attacker.example/robot/send?access_token=value"
            ))
        );

        assertEquals(400, exception.getCode());
        verify(client, never()).send(
            any(HttpRequest.class),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()
        );
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> response(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    private String requestBody(HttpRequest request) {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        CompletableFuture<byte[]> completed = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                completed.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                completed.complete(output.toByteArray());
            }
        });
        return new String(completed.join(), StandardCharsets.UTF_8);
    }
}
