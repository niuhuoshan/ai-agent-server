package group.aitools.nhs.platform.data.service;

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
import java.util.Map;

/** Restricts every JDBC endpoint to an explicit engine scheme and approved network range. */
@Component
public class DataSourceEndpointPolicy {

    private final boolean allowPrivateEndpoints;
    private final boolean allowLocalEndpoints;

    public DataSourceEndpointPolicy(
        @Value("${agent.platform.data.allow-private-endpoints:true}") boolean allowPrivateEndpoints,
        @Value("${agent.platform.data.allow-local-endpoints:false}") boolean allowLocalEndpoints
    ) {
        this.allowPrivateEndpoints = allowPrivateEndpoints;
        this.allowLocalEndpoints = allowLocalEndpoints;
    }

    public DataConnectionTarget normalize(
        String dbType,
        String endpointUrl,
        String databaseName
    ) {
        DataSourceType type = DataSourceType.require(dbType);
        String raw = endpointUrl == null ? "" : endpointUrl.strip();
        String database = databaseName == null ? "" : databaseName.strip();
        if (raw.isEmpty() || raw.length() > 1024 || raw.indexOf('\\') >= 0
            || !database.matches("[A-Za-z0-9][A-Za-z0-9_$.-]{0,62}")) {
            throw badRequest(type.label() + " Endpoint 或数据库名无效");
        }
        try {
            URI uri = new URI(raw);
            if (!type.endpointScheme().equalsIgnoreCase(uri.getScheme())
                || !uri.isAbsolute() || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty()) || uri.getPort() == 0) {
                throw endpointFormat(type);
            }
            int port = uri.getPort() == -1 ? type.defaultPort() : uri.getPort();
            if (port < 1 || port > 65535) {
                throw badRequest(type.label() + " 端口无效");
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!allowLocalEndpoints && isLocalHostname(host)) {
                throw badRequest("数据源 Endpoint 不允许使用本机地址");
            }
            String renderedHost = renderHost(host);
            return new DataConnectionTarget(
                type.id(),
                type.endpointScheme() + "://" + renderedHost + ':' + port,
                host,
                port,
                database
            );
        } catch (URISyntaxException exception) {
            throw endpointFormat(type);
        }
    }

    public void validateNetworkTarget(DataConnectionTarget target) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(target.host());
            if (addresses.length == 0) {
                throw new UnknownHostException(target.host());
            }
            for (InetAddress address : addresses) {
                if (isAlwaysForbidden(address)
                    || (!allowLocalEndpoints && address.isLoopbackAddress())
                    || (!allowPrivateEndpoints && isPrivateAddress(address))) {
                    throw badRequest("数据源 Endpoint 指向未获准的网络地址");
                }
            }
        } catch (UnknownHostException exception) {
            throw new ServiceException("数据源 Endpoint 域名无法解析", 502);
        }
    }

    private boolean isLocalHostname(String host) {
        return "localhost".equals(host) || host.endsWith(".localhost") || host.endsWith(".local");
    }

    private boolean isAlwaysForbidden(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first == 0 || first >= 224 || (first == 169 && second == 254)
                || (first == 192 && second == 0 && (third == 0 || third == 2))
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113);
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return (first == 0xfe && (second & 0xc0) == 0x80)
                || (first == 0x20 && second == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d
                    && Byte.toUnsignedInt(bytes[3]) == 0xb8);
        }
        return true;
    }

    private boolean isPrivateAddress(InetAddress address) {
        if (address.isSiteLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 100 && second >= 64 && second <= 127;
        }
        return address instanceof Inet6Address && (Byte.toUnsignedInt(bytes[0]) & 0xfe) == 0xfc;
    }

    private ServiceException endpointFormat(DataSourceType type) {
        return badRequest(
            "Endpoint 必须采用 " + type.endpointScheme()
                + "://host[:port] 格式且不得包含凭证、路径或参数"
        );
    }

    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    private static String renderHost(String host) {
        return host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    }

    public record DataConnectionTarget(
        String dbType,
        String normalizedEndpoint,
        String host,
        int port,
        String database
    ) {
        public String renderedHost() {
            return renderHost(host);
        }

        /** Compatibility accessor retained for the original PostgreSQL policy tests. */
        public String jdbcUrl() {
            DataSourceType type = DataSourceType.require(dbType);
            return type.jdbcUrl(this, Map.of("sslMode", type.defaultSslMode()));
        }
    }
}
