package com.baseai.platform.service;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.LlmModel;
import com.baseai.platform.domain.LlmProvider;
import com.baseai.platform.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 覆盖供应商地址至少一项、同步关系和按模型类型解析。 */
class ProviderEndpointTest {
    private final LlmProviderRepository providers=mock(LlmProviderRepository.class);
    private final LlmModelRepository models=mock(LlmModelRepository.class);
    private final ConfigCryptoService crypto=mock(ConfigCryptoService.class);
    private final LlmManagementService service=new LlmManagementService(providers,models,mock(LlmRouteRepository.class),mock(DictionaryDataRepository.class),crypto,mock(RestClient.class));
    private LlmManagementService.ProviderCommand command(String base,String image,String video,Boolean imageSync,Boolean videoSync) {
        return new LlmManagementService.ProviderCommand("P","Provider",base,"key",4,"PROVIDER",60,null,true,image,video,imageSync,videoSync);
    }
    private LlmProvider provider(String base,String image,String video,boolean imageSync,boolean videoSync) {
        when(providers.save(any())).thenAnswer(i->i.getArgument(0)); when(crypto.encrypt("key")).thenReturn("encrypted"); when(crypto.decrypt("encrypted")).thenReturn("key");
        var view=service.createProvider(command(base,image,video,imageSync,videoSync));
        var p=new LlmProvider(); p.setCode("P"); p.setName("Provider"); p.setBaseUrl(view.baseUrl()); p.setImageUrl(view.imageUrl()); p.setVideoUrl(view.videoUrl()); p.setImageSameAsBase(view.imageSameAsBase()); p.setVideoSameAsImage(view.videoSameAsImage()); p.setApiKeysEncrypted("encrypted"); p.setEnabled(true); ReflectionTestUtils.setField(p,"id",1L); return p;
    }
    private void model(String type) { var m=new LlmModel(); m.setProviderId(1L); m.setEnabled(true); m.setModelName("model"); m.setSupportedModelTypes(List.of(type)); when(models.findById(9L)).thenReturn(Optional.of(m)); }
    @Test void acceptsAnySingleEndpointAndRejectsEmpty() {
        when(providers.save(any())).thenAnswer(i->i.getArgument(0));
        for (var values: List.of(command("https://base.example",null,null,false,false),command(null,"https://image.example",null,false,false),command(null,null,"https://video.example",false,false))) {
            when(providers.findByCode("P")).thenReturn(Optional.empty()); assertDoesNotThrow(() -> service.createProvider(values));
        }
        assertEquals("llm.serviceUrlRequired",assertThrows(BusinessException.class,()->service.createProvider(command(" "," "," ",false,false))).getMessageKey());
    }
    @Test void resolvesMediaEndpointsAndSynchronization() {
        var p=provider("https://base.example","https://image.example","https://video.example",false,false); when(providers.findById(1L)).thenReturn(Optional.of(p));
        for (var typeHost: List.of(List.of("text_model","https://base.example"),List.of("image_model","https://image.example"),List.of("video_model","https://video.example"))) { model(typeHost.get(0)); assertEquals(typeHost.get(1),service.resolveModel(9L,typeHost.get(0),false,null).candidates().get(0).baseUrl()); }
        var synced=provider("https://base.example",null,null,true,true); when(providers.findById(1L)).thenReturn(Optional.of(synced)); model("video_model"); assertEquals("https://base.example",service.resolveModel(9L,"video_model",false,null).candidates().get(0).baseUrl());
    }
    @Test void rejectsUnsafeMediaEndpoint() {
        when(providers.findByCode("P")).thenReturn(Optional.empty());
        assertEquals("llm.serviceUrlInvalid",assertThrows(BusinessException.class,()->service.createProvider(command("file:///tmp",null,null,false,false))).getMessageKey());
    }
}
