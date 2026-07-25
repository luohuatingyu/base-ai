package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class ApiKeySecretService {
    private static final String PREFIX = "sk-";
    private static final String TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int TOKEN_LENGTH = 32;
    private static final int KEY_ID_LENGTH = 12;
    private final SecureRandom secureRandom = new SecureRandom();
    private final byte[] hashSecret;

    public ApiKeySecretService(PlatformProperties properties) {
        this.hashSecret = resolveHashSecret(properties.getApiKey().getHashSecret());
    }

    /** 创建新的 API Key 标识、明文和不可逆摘要。 */
    public GeneratedApiKey generate() {
        String secret = randomAlphanumeric(TOKEN_LENGTH);
        String keyId = secret.substring(0, KEY_ID_LENGTH);
        return new GeneratedApiKey(keyId, PREFIX + secret, hash(secret));
    }

    /** 解析 API Key 并拒绝非法或超长输入。 */
    public ParsedApiKey parse(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.length() != PREFIX.length() + TOKEN_LENGTH || !rawApiKey.startsWith(PREFIX)) {
            throw BusinessException.unauthorized("API Key 无效");
        }
        String secret = rawApiKey.substring(PREFIX.length());
        if (!secret.matches("[A-Za-z0-9]{32}")) throw BusinessException.unauthorized("API Key 无效");
        return new ParsedApiKey(secret.substring(0, KEY_ID_LENGTH), secret);
    }

    /** 使用恒定时间比较验证 API Key Secret。 */
    public boolean matches(String secret, String expectedHash) {
        if (secret == null || expectedHash == null) return false;
        return MessageDigest.isEqual(hash(secret).getBytes(StandardCharsets.US_ASCII), expectedHash.getBytes(StandardCharsets.US_ASCII));
    }

    /** 返回适合列表展示且不包含 Secret 的 Key 前缀。 */
    public String displayPrefix(String keyId) {
        return PREFIX + keyId + "****";
    }

    /** 计算 Secret 的 HMAC-SHA256 摘要。 */
    private String hash(String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hashSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("API Key 摘要计算失败", exception);
        }
    }

    /** 读取 Base64 或普通文本形式的摘要密钥。 */
    private byte[] resolveHashSecret(String value) {
        if (value == null || value.isBlank()) throw new IllegalStateException("API Key 摘要密钥不能为空");
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length >= 32) return decoded;
        } catch (IllegalArgumentException ignored) {
            // 非 Base64 配置按普通文本处理。
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("API Key 摘要密钥至少需要 32 字节");
        return bytes;
    }

    /** 生成指定长度的大小写字母和数字随机串。 */
    private String randomAlphanumeric(int length) {
        StringBuilder value = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            value.append(TOKEN_ALPHABET.charAt(secureRandom.nextInt(TOKEN_ALPHABET.length())));
        }
        return value.toString();
    }

    public record GeneratedApiKey(String keyId, String rawApiKey, String secretHash) {}
    public record ParsedApiKey(String keyId, String secret) {}
}
