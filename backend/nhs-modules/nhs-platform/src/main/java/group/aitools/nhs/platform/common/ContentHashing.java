package group.aitools.nhs.platform.common;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 处理{@code sha256}并返回对应结果。
 *
 * 表示{@code ContentHashing}相关的领域对象。
 * Stable SHA-256 hashing for immutable task snapshots. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ContentHashing {

    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 处理{@code sha256}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public static String sha256(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
