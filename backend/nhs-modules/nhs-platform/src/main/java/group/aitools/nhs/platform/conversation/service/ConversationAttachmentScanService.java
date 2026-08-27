package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 负责会话附件Scan相关的业务编排与领域规则处理。
 *
 * Makes attachment scanning an explicit gate before attachment metadata is persisted.
 *
 * The built-in policy only detects the standard EICAR test signature. Deployments that
 * require full antivirus coverage can switch to {@code external} and provide a real
 * {@link ConversationAttachmentScanner} bean. An absent external provider is reported as
 * unavailable instead of being treated as a clean result.
 */
@Service
public class ConversationAttachmentScanService {

    private static final int SERVICE_UNAVAILABLE = 503;
    private static final byte[] EICAR_SIGNATURE = (
        "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
    ).getBytes(StandardCharsets.US_ASCII);

    private final String mode;
    private final List<ConversationAttachmentScanner> scanners;

    /**
     * 创建 {@code ConversationAttachmentScanService} 实例并初始化所需依赖。
     *
     * @param mode {@code mode}参数
     * @param scanners {@code scanners}参数
     */
    @Autowired
    public ConversationAttachmentScanService(
        @Value("${agent.platform.conversation.attachment-scan.mode:builtin-signature}") String mode,
        List<ConversationAttachmentScanner> scanners
    ) {
        this.mode = normalizeMode(mode);
        this.scanners = scanners == null ? List.of() : List.copyOf(scanners);
    }

    /**
     * 校验{@code Clean}，并在条件不满足时终止处理。
     *
     * @param fileName 名称
     * @param mimeType 业务类型
     * @param content 待处理内容
     */
    public void requireClean(String fileName, String mimeType, byte[] content) {
        if (content == null) {
            throw unavailable("附件病毒扫描没有收到内容");
        }
        ConversationAttachmentScanner.ScanResult result = scan(fileName, mimeType, content);
        if (result == null || result.decision() == ConversationAttachmentScanner.Decision.UNAVAILABLE) {
            String reason = result == null ? "没有可用的扫描 Provider" : result.reason();
            throw unavailable("附件病毒扫描不可用" + suffix(reason));
        }
        if (result.decision() == ConversationAttachmentScanner.Decision.INFECTED) {
            throw new ServiceException(
                "附件未通过病毒扫描" + suffix(result.reason()),
                group.aitools.nhs.common.core.constant.HttpStatus.BAD_REQUEST
            );
        }
    }

    /**
     * 处理{@code scan}并返回对应结果。
     *
     * @param fileName 名称
     * @param mimeType 业务类型
     * @param content 待处理内容
     * @return 处理结果
     */
    ConversationAttachmentScanner.ScanResult scan(
        String fileName,
        String mimeType,
        byte[] content
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if ("external".equals(mode)) {
            for (ConversationAttachmentScanner scanner : scanners) {
                try {
                    ConversationAttachmentScanner.ScanResult result = scanner.scan(fileName, mimeType, content);
                    if (result != null) {
                        return result;
                    }
                } catch (RuntimeException exception) {
                    return new ConversationAttachmentScanner.ScanResult(
                        ConversationAttachmentScanner.Decision.UNAVAILABLE,
                        "external",
                        "外部扫描 Provider 执行失败"
                    );
                }
            }
            return new ConversationAttachmentScanner.ScanResult(
                ConversationAttachmentScanner.Decision.UNAVAILABLE,
                "external",
                "未配置外部扫描 Provider"
            );
        }
        if (containsSignature(content, EICAR_SIGNATURE)) {
            return new ConversationAttachmentScanner.ScanResult(
                ConversationAttachmentScanner.Decision.INFECTED,
                "builtin-signature",
                "检测到 EICAR 测试病毒签名"
            );
        }
        return new ConversationAttachmentScanner.ScanResult(
            ConversationAttachmentScanner.Decision.CLEAN,
            "builtin-signature",
            "仅完成内置 EICAR 签名检查"
        );
    }

    /**
     * 处理{@code mode}并返回对应结果。
     *
     * @return 处理结果
     */
    String mode() {
        return mode;
    }

    /**
     * 处理{@code containsSignature}并返回对应结果。
     *
     * @param content 待处理内容
     * @param signature {@code signature}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean containsSignature(byte[] content, byte[] signature) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (content.length < signature.length) {
            return false;
        }
        outer:
        for (int start = 0; start <= content.length - signature.length; start++) {
            for (int offset = 0; offset < signature.length; offset++) {
                if (content[start + offset] != signature[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 处理{@code normalizeMode}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeMode(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!normalized.equals("builtin-signature") && !normalized.equals("external")) {
            throw new IllegalStateException(
                "agent.platform.conversation.attachment-scan.mode 必须是 builtin-signature 或 external"
            );
        }
        return normalized;
    }

    /**
     * 处理{@code unavailable}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException unavailable(String message) {
        return new ServiceException(message, SERVICE_UNAVAILABLE);
    }

    /**
     * 处理{@code suffix}并返回对应结果。
     *
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    private String suffix(String reason) {
        return reason == null || reason.isBlank() ? "" : "：" + reason;
    }
}
