package com.baseai.platform.automation;

import com.baseai.platform.config.PlatformProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigCryptoServiceTest {

    /** 验证敏感配置经 AES-GCM 加密后可以恢复且不保留明文。 */
    @Test
    void encryptsAndDecryptsConfiguration() {
        PlatformProperties properties = new PlatformProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        ConfigCryptoService service = new ConfigCryptoService(properties);
        String encrypted = service.encrypt("{\"Authorization\":\"Bearer secret\"}");
        assertThat(encrypted).startsWith("enc:").doesNotContain("Bearer secret");
        assertThat(service.decrypt(encrypted)).isEqualTo("{\"Authorization\":\"Bearer secret\"}");
    }
}
