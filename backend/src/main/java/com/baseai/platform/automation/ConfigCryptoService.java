package com.baseai.platform.automation;

import com.baseai.platform.config.PlatformProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class ConfigCryptoService {
    private static final String LEGACY_PREFIX = "enc:";
    private static final String VERSIONED_PREFIX = "enc:v1:";
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private final Map<String, SecretKeySpec> keys;
    private final String activeKeyId;
    private final SecureRandom secureRandom = new SecureRandom();

    public ConfigCryptoService(PlatformProperties properties) {
        this.keys = new LinkedHashMap<>();
        this.keys.put("legacy", decodeKey(properties.getConfigEncryptionKey()));
        String configured = properties.getConfigEncryptionKeys();
        if (configured != null && !configured.isBlank()) {
            for (String item : configured.split(",")) {
                String[] pair = item.trim().split("=", 2);
                if (pair.length != 2 || !KEY_ID.matcher(pair[0].trim()).matches()) {
                    throw new IllegalStateException("APP_CONFIG_ENCRYPTION_KEYS 格式无效");
                }
                keys.put(pair[0].trim(), decodeKey(pair[1].trim()));
            }
        }
        this.activeKeyId = properties.getConfigEncryptionActiveKeyId() == null
            || properties.getConfigEncryptionActiveKeyId().isBlank() ? "legacy" : properties.getConfigEncryptionActiveKeyId().trim();
        if (!keys.containsKey(activeKeyId)) throw new IllegalStateException("配置加密活动密钥不存在");
    }

    /** 使用 AES-GCM 加密接口配置中的敏感文本。 */
    public String encrypt(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            byte[] iv = new byte[12];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return VERSIONED_PREFIX + activeKeyId + ":" + Base64.getEncoder().encodeToString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("接口配置加密失败", exception);
        }
    }

    /** 解密使用当前 AES-GCM 格式保存的敏感文本。 */
    public String decrypt(String value) {
        if (value == null || value.isBlank()) return "";
        String encoded;
        SecretKeySpec decryptKey;
        if (value.startsWith(VERSIONED_PREFIX)) {
            int separator = value.indexOf(':', VERSIONED_PREFIX.length());
            if (separator < 0) throw new IllegalStateException("接口配置密文格式无效");
            String keyId = value.substring(VERSIONED_PREFIX.length(), separator);
            decryptKey = keys.get(keyId);
            encoded = value.substring(separator + 1);
            if (decryptKey == null) throw new IllegalStateException("接口配置密钥版本不存在");
        } else if (value.startsWith(LEGACY_PREFIX)) {
            decryptKey = keys.get("legacy");
            encoded = value.substring(LEGACY_PREFIX.length());
        } else {
            throw new IllegalStateException("接口配置密文格式无效");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(encoded);
            if (payload.length < 12 + 16) throw new IllegalStateException("接口配置密文长度无效");
            byte[] iv = java.util.Arrays.copyOfRange(payload, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(payload, 12, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, decryptKey, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("接口配置解密失败", exception);
        }
    }

    /** 返回密文是否已经使用当前活动密钥，供渐进重加密任务判断。 */
    public boolean usesActiveKey(String value) {
        return value != null && value.startsWith(VERSIONED_PREFIX + activeKeyId + ":");
    }

    /** 严格解析 Base64 编码的 AES-256 密钥。 */
    private SecretKeySpec decodeKey(String encoded) {
        try {
            byte[] value = Base64.getDecoder().decode(encoded == null ? "" : encoded);
            if (value.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(value, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("配置加密密钥必须是 Base64 编码的 32 字节值", exception);
        }
    }
}
