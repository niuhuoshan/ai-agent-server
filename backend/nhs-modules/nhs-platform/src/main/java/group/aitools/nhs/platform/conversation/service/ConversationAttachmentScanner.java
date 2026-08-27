package group.aitools.nhs.platform.conversation.service;

/**
 * 定义会话附件Scanner相关能力的服务契约。
 * Optional external malware scanner used by the attachment scan policy. */
public interface ConversationAttachmentScanner {

    /**
 * 处理{@code scan}并返回对应结果。
 *
     * Scans one already content-validated attachment.
     *
     * @return a result when this scanner can handle the attachment, otherwise {@code null}
     */
    ScanResult scan(String fileName, String mimeType, byte[] content);

    /**
     * 封装{@code Scan}相关的不可变数据。
     */
    record ScanResult(Decision decision, String engine, String reason) {

        /**
         * 创建 {@code ScanResult} 实例并初始化所需依赖。
         *
         * @param decision {@code decision}参数
         * @param engine {@code engine}参数
         * @param reason {@code reason}参数
         */
        public ScanResult {
            if (decision == null) {
                throw new IllegalArgumentException("附件扫描结果缺少决策");
            }
        }
    }

    /**
     * 定义{@code Decision}相关的可选值。
     */
    enum Decision {
        CLEAN,
        INFECTED,
        UNAVAILABLE
    }
}
