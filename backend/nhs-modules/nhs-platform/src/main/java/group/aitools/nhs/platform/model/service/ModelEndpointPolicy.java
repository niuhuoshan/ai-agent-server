package group.aitools.nhs.platform.model.service;

import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * 表示模型Endpoint策略相关的领域对象。
 * Validates model endpoints and blocks SSRF targets unless explicitly enabled. */
@Component
public class ModelEndpointPolicy {

    private static final Set<String> PROVIDERS = Set.of("openai", "openai-compatible");
    private static final String OPENAI_ENDPOINT = "https://api.openai.com/v1";

    private final boolean allowHttpEndpoints;
    private final boolean allowPrivateEndpoints;

    public ModelEndpointPolicy(
        @Value("${agent.platform.model.allow-http-endpoints:false}") boolean allowHttpEndpoints,
        @Value("${agent.platform.model.allow-private-endpoints:false}") boolean allowPrivateEndpoints
    ) {
        this.allowHttpEndpoints = allowHttpEndpoints;
        this.allowPrivateEndpoints = allowPrivateEndpoints;
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param providerType 业务类型
     * @param endpointUrl {@code endpointUrl}参数
     * @return 处理结果
     */
    public URI normalize(String providerType, String endpointUrl) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        String provider = normalizeProvider(providerType);
        String rawEndpoint = endpointUrl == null ? "" : endpointUrl.strip();
        if (rawEndpoint.isEmpty()) {
            if (!"openai".equals(provider)) {
                throw badRequest("OpenAI 兼容模型必须配置 Endpoint URL");
            }
            rawEndpoint = OPENAI_ENDPOINT;
        }
        if (rawEndpoint.length() > 512 || rawEndpoint.indexOf('\\') >= 0) {
            throw badRequest("模型 Endpoint URL 无效");
        }
        try {
            URI uri = new URI(rawEndpoint);
            validateUri(uri);
            String normalized = uri.toString();
            if (normalized.endsWith("/") && uri.getPath() != null && uri.getPath().length() > 1) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return new URI(normalized);
        } catch (URISyntaxException exception) {
            throw badRequest("模型 Endpoint URL 无效");
        }
    }

    /**
     * 校验{@code NetworkTarget}，并在条件不满足时终止处理。
     *
     * @param endpoint {@code endpoint}参数
     */
    public void validateNetworkTarget(URI endpoint) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        String host = endpoint.getHost();
        if (allowPrivateEndpoints) {
            return;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new UnknownHostException(host);
            }
            for (InetAddress address : addresses) {
                if (isForbiddenAddress(address)) {
                    throw badRequest("模型 Endpoint 不允许访问本机、内网或保留地址");
                }
            }
        } catch (UnknownHostException exception) {
            throw new ServiceException("模型 Endpoint 域名无法解析", 502);
        }
    }

    /**
     * 处理normalize提供方并返回对应结果。
     *
     * @param providerType 业务类型
     * @return 处理结果
     */
    private String normalizeProvider(String providerType) {
        String provider = providerType == null ? "" : providerType.strip().toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(provider)) {
            throw badRequest("不支持的模型供应商类型");
        }
        return provider;
    }

    /**
     * 校验{@code Uri}，并在条件不满足时终止处理。
     *
     * @param uri {@code uri}参数
     */
    private void validateUri(URI uri) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!Set.of("http", "https").contains(scheme)) {
            throw badRequest("模型 Endpoint 仅支持 HTTP/HTTPS");
        }
        if (!"https".equals(scheme) && !allowHttpEndpoints) {
            throw badRequest("模型 Endpoint 默认必须使用 HTTPS");
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
            || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() == 0) {
            throw badRequest("模型 Endpoint 必须是安全的绝对 URL");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!allowPrivateEndpoints && isLocalHostname(host)) {
            throw badRequest("模型 Endpoint 不允许使用本机或内部主机名");
        }
        String path = uri.getPath();
        if (path != null) {
            for (String segment : path.split("/")) {
                if (".".equals(segment) || "..".equals(segment)) {
                    throw badRequest("模型 Endpoint 路径无效");
                }
            }
        }
        if (!allowPrivateEndpoints && looksLikeIpLiteral(host)) {
            try {
                if (isForbiddenAddress(InetAddress.getByName(host))) {
                    throw badRequest("模型 Endpoint 不允许访问本机、内网或保留地址");
                }
            } catch (UnknownHostException exception) {
                throw badRequest("模型 Endpoint IP 地址无效");
            }
        }
    }

    /**
     * 判断{@code LocalHostname}是否满足要求。
     *
     * @param host {@code host}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isLocalHostname(String host) {
        return "localhost".equals(host)
            || host.endsWith(".localhost")
            || host.endsWith(".local")
            || host.endsWith(".internal")
            || host.endsWith(".home.arpa")
            || "metadata.google.internal".equals(host);
    }

    /**
     * 处理{@code looksLikeIpLiteral}并返回对应结果。
     *
     * @param host {@code host}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean looksLikeIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }

    /**
     * 判断{@code ForbiddenAddress}是否满足要求。
     *
     * @param address {@code address}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isForbiddenAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
            || address.isLinkLocalAddress() || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first == 0 || first == 10 || first == 127 || first >= 224
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 192 && second == 0 && (third == 0 || third == 2))
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113);
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return (first & 0xfe) == 0xfc
                || (first == 0x20 && second == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8);
        }
        return true;
    }

    /**
     * 处理{@code badRequest}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }
}
