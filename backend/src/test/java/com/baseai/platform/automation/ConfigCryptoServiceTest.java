package com.baseai.platform.automation;

import com.baseai.platform.config.PlatformProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigCryptoServiceTest {
    private ConfigCryptoService service;

    /** 使用固定长度测试密钥创建敏感配置加解密服务。 */
    @BeforeEach
    void setUp() {
        PlatformProperties properties = new PlatformProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        service = new ConfigCryptoService(properties);
    }

    /** 当前密文格式应支持完整加密和解密往返。 */
    @Test
    void encryptsAndDecryptsCurrentCiphertextFormat() {
        String encrypted = service.encrypt("secret-value");

        assertTrue(encrypted.startsWith("enc:"));
        assertEquals("secret-value", service.decrypt(encrypted));
    }

    /** 非当前密文格式不得作为明文直接返回。 */
    @Test
    void rejectsPlaintextValues() {
        assertThrows(IllegalStateException.class, () -> service.decrypt("plain-secret"));
    }

    /** 空敏感配置保持为空，避免可选字段产生无效密文。 */
    @Test
    void keepsBlankValuesEmpty() {
        assertEquals("", service.decrypt(null));
        assertEquals("", service.decrypt(""));
    }
}
