package com.baseai.platform.security;

import org.springframework.http.HttpHeaders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** 内部 HTTP 请求的 HMAC-SHA256 签名协议。 */
public final class InternalRequestSigner {
    public static final String TIMESTAMP = "X-Internal-Timestamp";
    public static final String NONCE = "X-Internal-Nonce";
    public static final String TARGET = "X-Internal-Target";
    public static final String CONTENT_SHA256 = "X-Internal-Content-SHA256";
    public static final String SIGNATURE = "X-Internal-Signature";
    private static final HexFormat HEX = HexFormat.of();

    private InternalRequestSigner() { }

    /** 使用当前时间和随机 nonce 生成不可重放的签名请求头。 */
    public static Map<String, String> headers(String secret, String method, URI uri, byte[] body) {
        return headers(secret, method, target(uri), body, Instant.now().getEpochSecond(),
            UUID.randomUUID().toString().replace("-", ""));
    }

    /** 为经过可信反向代理改写后的最终目标路径生成签名请求头。 */
    public static Map<String, String> headers(String secret, String method, String target, byte[] body) {
        return headers(secret, method, target, body, Instant.now().getEpochSecond(),
            UUID.randomUUID().toString().replace("-", ""));
    }

    /** 使用确定时间和 nonce 生成签名，供协议测试跨语言复核。 */
    public static Map<String, String> headers(String secret, String method, String target, byte[] body,
                                               long timestamp, String nonce) {
        validateTarget(target);
        String digest = sha256(body == null ? new byte[0] : body);
        String canonical = canonical(method, target, timestamp, nonce, digest);
        return Map.of(TIMESTAMP, Long.toString(timestamp), NONCE, nonce, TARGET, target,
            CONTENT_SHA256, digest, SIGNATURE, hmac(secret, canonical));
    }

    /** 把签名字段写入 Spring HTTP 请求头。 */
    public static void apply(HttpHeaders headers, Map<String, String> signature) {
        signature.forEach(headers::set);
        headers.remove("X-Internal-Token");
    }

    /** 校验时间、目标、正文摘要和 HMAC，不处理调用方维护的 nonce 重放缓存。 */
    public static boolean verify(String secret, String method, String actualTarget, byte[] body,
                                 String timestamp, String nonce, String signedTarget,
                                 String digest, String signature, Instant now, long maximumSkewSeconds) {
        try {
            if (secret == null || secret.length() < 24 || timestamp == null || !timestamp.matches("[0-9]{1,12}")
                || nonce == null || !nonce.matches("[a-f0-9]{32}")
                || digest == null || !digest.matches("[a-f0-9]{64}")
                || signature == null || !signature.matches("[a-f0-9]{64}")) return false;
            validateTarget(signedTarget);
            if (!signedTarget.equals(actualTarget)) return false;
            long seconds = Long.parseLong(timestamp);
            if (Math.abs(now.getEpochSecond() - seconds) > maximumSkewSeconds) return false;
            String actualDigest = sha256(body == null ? new byte[0] : body);
            if (!MessageDigest.isEqual(digest.getBytes(StandardCharsets.US_ASCII),
                actualDigest.getBytes(StandardCharsets.US_ASCII))) return false;
            String expected = hmac(secret, canonical(method, signedTarget, seconds, nonce, digest));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** 从 URI 保留原始编码路径和查询参数，生成 HTTP request-target。 */
    public static String target(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) path = "/";
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    /** 计算签名协议使用的小写 SHA-256。 */
    private static String sha256(byte[] body) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** 计算小写 HMAC-SHA256。 */
    private static String hmac(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("HmacSHA256 unavailable", exception);
        }
    }

    /** 生成跨语言稳定的换行分隔规范串。 */
    private static String canonical(String method, String target, long timestamp, String nonce, String digest) {
        return method.toUpperCase(java.util.Locale.ROOT) + "\n" + target + "\n" + timestamp + "\n" + nonce + "\n" + digest;
    }

    /** 限制签名目标为不含控制字符的原点形式路径。 */
    private static void validateTarget(String target) {
        if (target == null || !target.startsWith("/") || target.length() > 4096
            || target.indexOf('\r') >= 0 || target.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("invalid internal request target");
        }
    }
}
