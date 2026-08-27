package group.aitools.nhs.platform.sandbox.service;

import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 表示沙箱策略相关的领域对象。
 */
@Component
public class SandboxPolicyValidator {

    private static final Pattern TEMPLATE = Pattern.compile("[a-z][a-z0-9._-]{1,63}");
    private static final Pattern HOST = Pattern.compile(
        "(?i)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?))*"
    );

    /**
     * 校验{@code validate}，并在条件不满足时终止处理。
     *
     * @param templateKey 模板Key参数
     * @param argv {@code argv}参数
     * @param workspacePath 工作空间Path参数
     * @param workspaceAccess 工作空间Access参数
     * @param networkPolicy network策略参数
     * @param allowedHosts {@code allowedHosts}参数
     * @param timeoutSeconds {@code timeoutSeconds}参数
     * @param memoryMb 记忆Mb参数
     * @param cpuMillis {@code cpuMillis}参数
     * @param pidsLimit 数量上限
     * @param maxOutputBytes {@code maxOutputBytes}参数
     * @param priority {@code priority}参数
     * @return 处理结果
     */
    public ValidatedPolicy validate(
        String templateKey,
        List<String> argv,
        String workspacePath,
        String workspaceAccess,
        String networkPolicy,
        List<String> allowedHosts,
        int timeoutSeconds,
        int memoryMb,
        int cpuMillis,
        int pidsLimit,
        int maxOutputBytes,
        int priority
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        String template = required(templateKey, 64, "执行模板");
        if (!TEMPLATE.matcher(template).matches()) {
            throw badRequest("执行模板键无效");
        }
        if (argv == null || argv.isEmpty() || argv.size() > 128) {
            throw badRequest("argv参数数量必须在1到128之间");
        }
        List<String> normalizedArgv = argv.stream().map(this::argument).toList();
        String workspace = workspace(workspacePath);
        String access = enumValue(workspaceAccess, Set.of("read_only", "read_write"), "工作区权限");
        String network = enumValue(networkPolicy, Set.of("none", "allowlist"), "网络策略");
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        if (allowedHosts != null) {
            if (allowedHosts.size() > 32) {
                throw badRequest("网络白名单不能超过32项");
            }
            for (String host : allowedHosts) {
                String normalized = required(host, 253, "网络白名单主机").toLowerCase(Locale.ROOT);
                if (!HOST.matcher(normalized).matches()
                    || "localhost".equals(normalized)
                    || normalized.endsWith(".localhost")) {
                    throw badRequest("网络白名单主机无效");
                }
                hosts.add(normalized);
            }
        }
        if ("none".equals(network) && !hosts.isEmpty()) {
            throw badRequest("禁网作业不能配置网络白名单");
        }
        if ("allowlist".equals(network) && hosts.isEmpty()) {
            throw badRequest("白名单网络策略必须配置主机");
        }
        range(timeoutSeconds, 1, 3600, "超时时间");
        range(memoryMb, 64, 32768, "内存上限");
        range(cpuMillis, 100, 16000, "CPU上限");
        range(pidsLimit, 16, 2048, "PID上限");
        range(maxOutputBytes, 1024, 10485760, "输出上限");
        range(priority, -100, 100, "优先级");
        return new ValidatedPolicy(
            template, normalizedArgv, workspace, access, network, List.copyOf(hosts),
            timeoutSeconds, memoryMb, cpuMillis, pidsLimit, maxOutputBytes, priority
        );
    }

    /**
     * 处理{@code argument}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String argument(String value) {
        if (value == null || value.length() > 4096) {
            throw badRequest("argv参数无效或过长");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 32 || character == 127) {
                throw badRequest("argv参数不能包含控制字符");
            }
        }
        return value;
    }

    /**
     * 处理工作空间并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String workspace(String value) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        String raw = value == null || value.isBlank() ? "." : value.strip();
        if (raw.length() > 512 || raw.indexOf('\\') >= 0 || raw.indexOf(':') >= 0) {
            throw badRequest("工作区路径无效");
        }
        try {
            Path path = Path.of(raw);
            if (path.isAbsolute()) {
                throw badRequest("工作区路径必须是相对路径");
            }
            Path normalized = path.normalize();
            if (normalized.startsWith("..")) {
                throw badRequest("工作区路径不能越界");
            }
            String result = normalized.toString();
            return result.isBlank() ? "." : result;
        } catch (InvalidPathException exception) {
            throw badRequest("工作区路径无效");
        }
    }

    /**
     * 校验{@code d}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String required(String value, int maxLength, String label) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw badRequest(label + "不能为空或过长");
        }
        return normalized;
    }

    /**
     * 处理{@code enumValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param allowed {@code allowed}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String enumValue(String value, Set<String> allowed, String label) {
        String normalized = value == null ? "" : value.strip();
        if (!allowed.contains(normalized)) {
            throw badRequest(label + "无效");
        }
        return normalized;
    }

    /**
     * 处理{@code range}相关逻辑。
     *
     * @param value {@code value}参数
     * @param min {@code min}参数
     * @param max {@code max}参数
     * @param label {@code label}参数
     */
    private void range(int value, int min, int max, String label) {
        if (value < min || value > max) {
            throw badRequest(label + "超出允许范围");
        }
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

    /**
     * 封装Validated策略相关的不可变数据。
     */
    public record ValidatedPolicy(
        String templateKey,
        List<String> argv,
        String workspacePath,
        String workspaceAccess,
        String networkPolicy,
        List<String> allowedHosts,
        int timeoutSeconds,
        int memoryMb,
        int cpuMillis,
        int pidsLimit,
        int maxOutputBytes,
        int priority
    ) {
    }
}
