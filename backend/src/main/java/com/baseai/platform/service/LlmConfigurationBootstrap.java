package com.baseai.platform.service;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.domain.*;
import com.baseai.platform.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class LlmConfigurationBootstrap implements ApplicationRunner {
    private final LlmProviderRepository providers; private final LlmModelRepository models; private final LlmRouteRepository routes; private final ConfigCryptoService crypto;
    @Value("${app.llm-bootstrap.base-url:}") private String baseUrl; @Value("${app.llm-bootstrap.api-keys:}") private String apiKeys; @Value("${app.llm-bootstrap.model:}") private String modelName; @Value("${app.llm-bootstrap.concurrency:4}") private int concurrency;
    public LlmConfigurationBootstrap(LlmProviderRepository providers,LlmModelRepository models,LlmRouteRepository routes,ConfigCryptoService crypto){this.providers=providers;this.models=models;this.routes=routes;this.crypto=crypto;}
    /** 首次启动时将环境变量模型配置写入 MySQL，之后由模型中心维护。 */
    @Override public void run(ApplicationArguments arguments){if(baseUrl.isBlank()||apiKeys.isBlank()||modelName.isBlank()||providers.count()>0)return;LlmProvider provider=new LlmProvider();provider.setCode("default");provider.setName("默认供应商");provider.setBaseUrl(baseUrl.replaceAll("/+$",""));provider.setApiKeysEncrypted(crypto.encrypt(apiKeys));provider.setConcurrencyLimit(concurrency);provider=providers.save(provider);LlmModel model=new LlmModel();model.setCode("default-chat");model.setName("默认对话模型");model.setProviderId(provider.getId());model.setModelName(modelName);model=models.save(model);LlmRoute route=new LlmRoute();route.setFeatureCode("chat");route.setName("通用对话");route.setCandidateModelIds(String.valueOf(model.getId()));routes.save(route);}
}
