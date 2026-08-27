package group.aitools.nhs.sandbox.runner.execution;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 表示{@code BoundedOutputCollector}相关的领域对象。
 */
final class BoundedOutputCollector implements Runnable {

    private final InputStream input;
    private final StringBuilder output = new StringBuilder();
    private final AtomicInteger remaining;
    private final AtomicBoolean exceeded;
    private final Consumer<String> chunkConsumer;

    /**
     * 创建 {@code BoundedOutputCollector} 实例并初始化所需依赖。
     *
     * @param input {@code input}参数
     * @param remaining {@code remaining}参数
     * @param exceeded {@code exceeded}参数
     */
    BoundedOutputCollector(InputStream input, AtomicInteger remaining, AtomicBoolean exceeded) {
        this(input, remaining, exceeded, ignored -> { });
    }

    /**
     * 创建 {@code BoundedOutputCollector} 实例并初始化所需依赖。
     *
     * @param input {@code input}参数
     * @param remaining {@code remaining}参数
     * @param exceeded {@code exceeded}参数
     * @param chunkConsumer {@code chunkConsumer}参数
     */
    BoundedOutputCollector(
        InputStream input,
        AtomicInteger remaining,
        AtomicBoolean exceeded,
        Consumer<String> chunkConsumer
    ) {
        this.input = input;
        this.remaining = remaining;
        this.exceeded = exceeded;
        this.chunkConsumer = chunkConsumer;
    }

    /**
     * 执行{@code run}相关的处理流程。
     */
    @Override
    public void run() {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        char[] buffer = new char[2048];
        String carry = "";
        try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                String chunk = carry + new String(buffer, 0, count);
                carry = "";
                if (Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))) {
                    carry = chunk.substring(chunk.length() - 1);
                    chunk = chunk.substring(0, chunk.length() - 1);
                }
                retain(chunk);
            }
            if (!carry.isEmpty()) {
                retain(carry);
            }
        } catch (IOException ignored) {
            // Stream closure is expected when a timed-out container is destroyed.
        }
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @return 处理结果
     */
    String text() {
        synchronized (output) {
            return output.toString();
        }
    }

    /**
     * 处理{@code retain}相关逻辑。
     *
     * @param chunk {@code chunk}参数
     */
    private void retain(String chunk) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (chunk.isEmpty()) {
            return;
        }
        String retained = reserve(chunk);
        if (retained.length() < chunk.length()) {
            exceeded.set(true);
        }
        if (retained.isEmpty()) {
            return;
        }
        synchronized (output) {
            output.append(retained);
        }
        chunkConsumer.accept(retained);
    }

    /**
     * 处理{@code reserve}并返回对应结果。
     *
     * @param chunk {@code chunk}参数
     * @return 处理结果
     */
    private String reserve(String chunk) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        while (true) {
            int available = remaining.get();
            if (available <= 0) {
                return "";
            }
            String retained = utf8Prefix(chunk, available);
            int acceptedBytes = retained.getBytes(StandardCharsets.UTF_8).length;
            if (acceptedBytes == 0) {
                return "";
            }
            if (remaining.compareAndSet(available, available - acceptedBytes)) {
                return retained;
            }
        }
    }

    /**
     * 处理{@code utf8Prefix}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxBytes {@code maxBytes}参数
     * @return 处理结果
     */
    private String utf8Prefix(String value, int maxBytes) {
        int index = 0;
        int bytes = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            int encodedBytes = utf8Bytes(codePoint);
            if (bytes + encodedBytes > maxBytes) {
                break;
            }
            bytes += encodedBytes;
            index += Character.charCount(codePoint);
        }
        return value.substring(0, index);
    }

    /**
     * 处理{@code utf8Bytes}并返回对应结果。
     *
     * @param codePoint {@code codePoint}参数
     * @return 处理结果
     */
    private int utf8Bytes(int codePoint) {
        if (codePoint <= 0x7f
            || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
            return 1;
        }
        if (codePoint <= 0x7ff) {
            return 2;
        }
        return codePoint <= 0xffff ? 3 : 4;
    }
}
