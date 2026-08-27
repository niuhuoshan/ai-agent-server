package group.aitools.nhs.platform.notification.service;

import cn.hutool.extra.mail.MailAccount;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import group.aitools.nhs.common.mail.core.MailBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 表示通知ChannelSender相关的领域对象。
 * Executes real, bounded test deliveries for supported personal notification channels. */
@Component
public class NotificationChannelSender {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;
    private static final Set<String> DINGTALK_HOSTS = Set.of(
        "oapi.dingtalk.com", "api.dingtalk.com"
    );
    private static final Set<String> WECHAT_WORK_HOSTS = Set.of("qyapi.weixin.qq.com");
    private static final String TEST_TITLE = "消息通知连通性测试";
    private static final String TEST_CONTENT = "AI 智能体工作平台通知渠道已配置成功，测试消息发送正常。";
    private static final Pattern EMAIL = Pattern.compile(
        "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+$"
    );

    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;
    private final boolean allowHttpWebhook;
    private final boolean allowPrivateSmtpHost;

    /**
     * 创建 {@code NotificationChannelSender} 实例并初始化所需依赖。
     *
     * @param jsonMapper {@code jsonMapper}参数
     * @param allowHttpWebhook allowHttp回调通知参数
     * @param allowPrivateSmtpHost {@code allowPrivateSmtpHost}参数
     */
    @Autowired
    public NotificationChannelSender(
        JsonMapper jsonMapper,
        @Value("${agent.platform.notification.allow-http-webhook:false}") boolean allowHttpWebhook,
        @Value("${agent.platform.notification.allow-private-smtp-host:false}") boolean allowPrivateSmtpHost
    ) {
        this(
            jsonMapper,
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(),
            allowHttpWebhook,
            allowPrivateSmtpHost
        );
    }

    /**
     * 创建 {@code NotificationChannelSender} 实例并初始化所需依赖。
     *
     * @param jsonMapper {@code jsonMapper}参数
     * @param httpClient http客户端参数
     * @param allowHttpWebhook allowHttp回调通知参数
     * @param allowPrivateSmtpHost {@code allowPrivateSmtpHost}参数
     */
    NotificationChannelSender(
        JsonMapper jsonMapper,
        HttpClient httpClient,
        boolean allowHttpWebhook,
        boolean allowPrivateSmtpHost
    ) {
        this.jsonMapper = jsonMapper;
        this.httpClient = httpClient;
        this.allowHttpWebhook = allowHttpWebhook;
        this.allowPrivateSmtpHost = allowPrivateSmtpHost;
    }

    /**
     * 处理{@code sendTest}并返回对应结果。
     *
     * @param channelType 业务类型
     * @param config {@code config}参数
     * @return 处理结果
     */
    public SendResult sendTest(String channelType, Map<String, Object> config) {
        return sendMessage(channelType, config, TEST_TITLE, TEST_CONTENT, null);
    }

    /**
 * 处理send消息并返回对应结果。
 *
     * Delivers a user-authored notification using an already resolved owner configuration.
     * The configuration map is server-owned and must never originate from tool arguments.
     * A null recipient uses the configured email recipients (or the SMTP account for tests).
     */
    public SendResult sendMessage(
        String channelType,
        Map<String, Object> config,
        String title,
        String content,
        String recipient
    ) {
        long started = System.nanoTime();
        switch (channelType) {
            case "dingtalk" -> sendDingTalk(config, title, content);
            case "wechat_work" -> sendWechatWork(config, title, content);
            case "email" -> sendEmail(config, title, content, recipient);
            default -> throw new ServiceException("不支持的通知渠道", HttpStatus.BAD_REQUEST);
        }
        return new SendResult(channelType, elapsedMillis(started));
    }

    /**
     * 处理{@code sendDingTalk}相关逻辑。
     *
     * @param config {@code config}参数
     * @param title {@code title}参数
     * @param content 待处理内容
     */
    private void sendDingTalk(Map<String, Object> config, String title, String content) {
        String webhook = required(config, "webhook_url", "钉钉 Webhook 地址");
        String secret = text(config.get("secret"));
        if (!secret.isBlank()) {
            webhook = signedDingTalkUrl(webhook, secret);
        }
        URI endpoint = webhook(webhook, DINGTALK_HOSTS, "钉钉");
        sendWebhook(endpoint, Map.of(
            "msgtype", "markdown",
            "markdown", Map.of(
                "title", messageTitle(title),
                "text", "### " + messageTitle(title) + "\n\n" + messageContent(content)
            )
        ), "钉钉");
    }

    /**
     * 处理{@code sendWechatWork}相关逻辑。
     *
     * @param config {@code config}参数
     * @param title {@code title}参数
     * @param content 待处理内容
     */
    private void sendWechatWork(Map<String, Object> config, String title, String content) {
        URI endpoint = webhook(
            required(config, "webhook_url", "企业微信 Webhook 地址"),
            WECHAT_WORK_HOSTS,
            "企业微信"
        );
        sendWebhook(endpoint, Map.of(
            "msgtype", "markdown",
            "markdown", Map.of(
                "content", "### " + messageTitle(title) + "\n\n" + messageContent(content)
            )
        ), "企业微信");
    }

    /**
     * 处理send回调通知相关逻辑。
     *
     * @param endpoint {@code endpoint}参数
     * @param payload {@code payload}参数
     * @param provider 提供方参数
     */
    private void sendWebhook(URI endpoint, Map<String, Object> payload, String provider) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                jsonMapper.writeValueAsString(payload), StandardCharsets.UTF_8
            ))
            .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofByteArray()
            );
            if (response.body().length > MAX_RESPONSE_BYTES) {
                throw new ServiceException(provider + "通知供应商响应超过 16KB 限制", 502);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ServiceException(
                    provider + "通知供应商请求失败（HTTP " + response.statusCode() + "）",
                    502
                );
            }
            JsonNode root = jsonMapper.readTree(response.body());
            JsonNode errorCode = root == null ? null : root.get("errcode");
            if (errorCode == null || errorCode.asInt(-1) != 0) {
                throw new ServiceException(provider + "通知供应商拒绝了消息", 502);
            }
        } catch (ServiceException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable(provider + "通知测试已中断");
        } catch (IOException exception) {
            throw unavailable(provider + "通知供应商当前不可用");
        } catch (RuntimeException exception) {
            throw new ServiceException(provider + "通知供应商响应格式无效", 502);
        }
    }

    /**
     * 处理{@code sendEmail}相关逻辑。
     *
     * @param config {@code config}参数
     * @param subject {@code subject}参数
     * @param content 待处理内容
     * @param recipient {@code recipient}参数
     */
    private void sendEmail(
        Map<String, Object> config, String subject, String content, String recipient
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        String host = required(config, "smtp_host", "SMTP 服务地址");
        int port = integer(config.get("smtp_port"), "SMTP 端口");
        String user = required(config, "smtp_user", "SMTP 账号");
        String password = required(config, "smtp_password", "SMTP 授权码");
        String senderName = text(config.get("sender_name"));
        String recipients = recipient == null || recipient.isBlank()
            ? text(config.get("recipients")) : recipient.strip();
        if (recipients.isBlank()) {
            recipients = user;
        }
        validateRecipients(recipients);
        validateSmtpTarget(host);

        MailAccount account = new MailAccount();
        account.setHost(host);
        account.setPort(port);
        account.setAuth(true);
        account.setUser(user);
        account.setPass(password);
        account.setFrom(senderName.isBlank() ? user : senderName + " <" + user + ">");
        account.setSslEnable(port == 465);
        account.setStarttlsEnable(port != 465);
        account.setSocketFactoryPort(port);
        account.setConnectionTimeout(10_000L);
        account.setTimeout(10_000L);
        try {
            MailBuilder.of(account)
                .to(recipients)
                .subject(messageTitle(subject))
                .text(messageContent(content))
                .send();
        } catch (RuntimeException exception) {
            throw unavailable("邮件通知供应商不可用或鉴权失败");
        }
    }

    /**
     * 处理回调通知并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @param allowedHosts {@code allowedHosts}参数
     * @param provider 提供方参数
     * @return 处理结果
     */
    private URI webhook(String raw, Set<String> allowedHosts, String provider) {
        if (raw.length() > 2_048 || raw.indexOf('\\') >= 0) {
            throw new ServiceException(provider + " Webhook 地址无效", HttpStatus.BAD_REQUEST);
        }
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!("https".equals(scheme) || allowHttpWebhook && "http".equals(scheme))
                || !allowedHosts.contains(host)
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getPath() == null
                || uri.getPath().isBlank()) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new ServiceException(
                provider + " Webhook 必须使用官方 HTTPS 地址",
                HttpStatus.BAD_REQUEST
            );
        }
    }

    /**
     * 处理{@code signedDingTalkUrl}并返回对应结果。
     *
     * @param webhook 回调通知参数
     * @param secret {@code secret}参数
     * @return 处理结果
     */
    private String signedDingTalkUrl(String webhook, String secret) {
        try {
            long timestamp = System.currentTimeMillis();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(
                (timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8)
            );
            String encoded = URLEncoder.encode(
                Base64.getEncoder().encodeToString(signature), StandardCharsets.UTF_8
            );
            return webhook + (webhook.contains("?") ? "&" : "?")
                + "timestamp=" + timestamp + "&sign=" + encoded;
        } catch (GeneralSecurityException exception) {
            throw new ServiceException("钉钉签名生成失败", HttpStatus.ERROR);
        }
    }

    /**
     * 校验{@code SmtpTarget}，并在条件不满足时终止处理。
     *
     * @param host {@code host}参数
     */
    private void validateSmtpTarget(String host) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (host.length() > 253 || host.contains(":") || host.contains("/") || host.contains("\\")) {
            throw new ServiceException("SMTP 服务地址无效", HttpStatus.BAD_REQUEST);
        }
        if (allowPrivateSmtpHost) {
            return;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new UnknownHostException(host);
            }
            for (InetAddress address : addresses) {
                if (isPrivate(address)) {
                    throw new ServiceException(
                        "SMTP 服务地址不允许访问本机、内网或保留地址",
                        HttpStatus.BAD_REQUEST
                    );
                }
            }
        } catch (UnknownHostException exception) {
            throw unavailable("SMTP 服务地址无法解析");
        }
    }

    /**
     * 判断{@code Private}是否满足要求。
     *
     * @param address {@code address}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
            || address.isLinkLocalAddress() || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 0 || first == 10 || first == 127 || first >= 224
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168);
        }
        return address instanceof Inet6Address
            && (Byte.toUnsignedInt(bytes[0]) & 0xfe) == 0xfc;
    }

    /**
     * 校验{@code d}，并在条件不满足时终止处理。
     *
     * @param config {@code config}参数
     * @param key {@code key}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String required(Map<String, Object> config, String key, String label) {
        String value = text(config.get(key));
        if (value.isBlank()) {
            throw new ServiceException(label + "不能为空", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    /**
     * 处理消息Title并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String messageTitle(String value) {
        String normalized = text(value);
        return normalized.isBlank() ? TEST_TITLE : normalized;
    }

    /**
     * 处理消息Content并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String messageContent(String value) {
        String normalized = text(value);
        return normalized.isBlank() ? TEST_CONTENT : normalized;
    }

    /**
     * 校验{@code Recipients}，并在条件不满足时终止处理。
     *
     * @param recipients {@code recipients}参数
     */
    private void validateRecipients(String recipients) {
        String[] values = recipients.replace(';', ',').split(",");
        if (values.length > 50) {
            throw new ServiceException("邮件收件人数量超过 50 个", HttpStatus.BAD_REQUEST);
        }
        for (String value : values) {
            String address = value.strip();
            if (address.isBlank() || address.length() > 320 || !EMAIL.matcher(address).matches()) {
                throw new ServiceException("邮件收件人地址无效", HttpStatus.BAD_REQUEST);
            }
        }
    }

    /**
     * 处理{@code integer}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private int integer(Object value, String label) {
        try {
            long result = value instanceof Number number
                ? number.longValue() : Long.parseLong(text(value));
            if (result < 1 || result > 65_535) {
                throw new NumberFormatException();
            }
            return Math.toIntExact(result);
        } catch (NumberFormatException exception) {
            throw new ServiceException(label + "无效", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 处理{@code elapsedMillis}并返回对应结果。
     *
     * @param started {@code started}参数
     * @return 处理结果
     */
    private long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    /**
     * 处理{@code unavailable}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException unavailable(String message) {
        return new ServiceException(message, 503);
    }

    /**
     * 封装{@code Send}相关的不可变数据。
     */
    public record SendResult(String channelType, long elapsedMs) {
    }
}
