package com.baseai.platform.automation;

import com.baseai.platform.config.PlatformProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class ConfigCryptoService {
    private static final String PREFIX = "enc:";
    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public ConfigCryptoService(PlatformProperties properties) {
        this.key = new SecretKeySpec(Base64.getDecoder().decode(properties.getConfigEncryptionKey()), "AES");
    }

    /** 使用 AES-GCM 加密接口配置中的敏感文本。 */
    public String encrypt(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            byte[] iv = new byte[12];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("接口配置加密失败", exception);
        }
    }

    /** 解密 AES-GCM 文本，并兼容未加密的历史值。 */
    public String decrypt(String value) {
        if (value == null || value.isBlank() || !value.startsWith(PREFIX)) return value == null ? "" : value;
        try {
            byte[] payload = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            byte[] iv = java.util.Arrays.copyOfRange(payload, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(payload, 12, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("接口配置解密失败", exception);
        }
    }
}
