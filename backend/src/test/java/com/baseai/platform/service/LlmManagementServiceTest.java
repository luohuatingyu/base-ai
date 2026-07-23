package com.baseai.platform.service;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.domain.LlmProvider;
import com.baseai.platform.repository.LlmModelRepository;
import com.baseai.platform.repository.LlmProviderRepository;
import com.baseai.platform.repository.LlmRouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class LlmManagementServiceTest {
    private LlmProviderRepository providerRepository;
    private ConfigCryptoService cryptoService;
    private LlmManagementService service;

    /** 为每个测试创建隔离的供应商服务依赖。 */
    @BeforeEach
    void setUp() {
        providerRepository = mock(LlmProviderRepository.class);
        cryptoService = mock(ConfigCryptoService.class);
        service = new LlmManagementService(providerRepository, mock(LlmModelRepository.class), mock(LlmRouteRepository.class), cryptoService, mock(RestClient.class));
    }

    /** 供应商列表必须继续只返回脱敏密钥，不能批量泄露明文。 */
    @Test
    void providerListMasksApiKeys() {
        LlmProvider provider = provider("encrypted-keys");
        when(providerRepository.findAll()).thenReturn(List.of(provider));

        assertEquals("******", service.providers().get(0).apiKeys());
        verifyNoInteractions(cryptoService);
    }

    /** 查看单个供应商时，应解密并将逗号、换行分隔的密钥统一为一行一个。 */
    @Test
    void providerApiKeysReturnsOneKeyPerLine() {
        LlmProvider provider = provider("encrypted-keys");
        when(providerRepository.findById(3L)).thenReturn(Optional.of(provider));
        when(cryptoService.decrypt("encrypted-keys")).thenReturn(" key-one, key-two\n\n key-three ");

        assertEquals("key-one\nkey-two\nkey-three", service.providerApiKeys(3L).apiKeys());
    }

    /** 创建供测试使用的供应商实体。 */
    private LlmProvider provider(String encryptedKeys) {
        LlmProvider provider = new LlmProvider();
        provider.setCode("TEST");
        provider.setName("Test provider");
        provider.setBaseUrl("https://example.test");
        provider.setApiKeysEncrypted(encryptedKeys);
        return provider;
    }
}
