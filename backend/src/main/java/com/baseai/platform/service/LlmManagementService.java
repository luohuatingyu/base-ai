package com.baseai.platform.service;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.*;
import com.baseai.platform.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.*;

@Service
public class LlmManagementService {
    private final LlmProviderRepository providerRepository;
    private final LlmModelRepository modelRepository;
    private final LlmRouteRepository routeRepository;
    private final ConfigCryptoService cryptoService;
    private final RestClient workerClient;

    public LlmManagementService(LlmProviderRepository providerRepository, LlmModelRepository modelRepository,
                                LlmRouteRepository routeRepository, ConfigCryptoService cryptoService,
                                @org.springframework.beans.factory.annotation.Qualifier("pythonWorkerRestClient") RestClient workerClient) {
        this.providerRepository=providerRepository;this.modelRepository=modelRepository;this.routeRepository=routeRepository;this.cryptoService=cryptoService;this.workerClient=workerClient;
    }

    /** 查询供应商并脱敏 API Key。 */
    public List<ProviderView> providers(){return providerRepository.findAll().stream().sorted(Comparator.comparing(LlmProvider::getId)).map(this::providerView).toList();}
    /** 创建模型供应商。 */ @Transactional public ProviderView createProvider(ProviderCommand command){if(providerRepository.findByCode(require(command.code(),"请输入供应商编码")).isPresent())throw new BusinessException("供应商编码已存在");return providerView(saveProvider(new LlmProvider(),command));}
    /** 更新模型供应商。 */ @Transactional public ProviderView updateProvider(Long id,ProviderCommand command){return providerView(saveProvider(providerRepository.findById(id).orElseThrow(()->BusinessException.notFound("供应商不存在")),command));}
    /** 删除未被模型使用的供应商。 */ @Transactional public void deleteProvider(Long id){if(modelRepository.findAll().stream().anyMatch(item->id.equals(item.getProviderId())))throw new BusinessException("供应商已被模型使用");providerRepository.deleteById(id);}

    /** 查询模型配置。 */ public List<LlmModel> models(){return modelRepository.findAll().stream().sorted(Comparator.comparing(LlmModel::getId)).toList();}
    /** 创建模型。 */ @Transactional public LlmModel createModel(ModelCommand command){if(modelRepository.findByCode(require(command.code(),"请输入模型编码")).isPresent())throw new BusinessException("模型编码已存在");return saveModel(new LlmModel(),command);}
    /** 更新模型。 */ @Transactional public LlmModel updateModel(Long id,ModelCommand command){return saveModel(modelRepository.findById(id).orElseThrow(()->BusinessException.notFound("模型不存在")),command);}
    /** 删除未被路由引用的模型。 */ @Transactional public void deleteModel(Long id){if(routeRepository.findAll().stream().anyMatch(route->parseIds(route.getCandidateModelIds()).contains(id)))throw new BusinessException("模型已被能力路由使用");modelRepository.deleteById(id);}

    /** 查询能力路由。 */ public List<RouteView> routes(){return routeRepository.findAll().stream().sorted(Comparator.comparing(LlmRoute::getFeatureCode)).map(this::routeView).toList();}
    /** 创建能力路由。 */ @Transactional public RouteView createRoute(RouteCommand command){if(routeRepository.findByFeatureCode(require(command.featureCode(),"请输入功能编码")).isPresent())throw new BusinessException("功能编码已存在");return routeView(saveRoute(new LlmRoute(),command));}
    /** 更新能力路由。 */ @Transactional public RouteView updateRoute(Long id,RouteCommand command){return routeView(saveRoute(routeRepository.findById(id).orElseThrow(()->BusinessException.notFound("能力路由不存在")),command));}
    /** 删除能力路由。 */ public void deleteRoute(Long id){routeRepository.deleteById(id);}

    /** 解析指定功能的候选模型列表，未配置时使用环境变量兜底。 */
    public List<WorkerCandidate> candidates(String featureCode){
        return routeRepository.findByFeatureCode(featureCode).filter(route->Boolean.TRUE.equals(route.getEnabled())).stream()
            .flatMap(route->parseIds(route.getCandidateModelIds()).stream()).map(modelRepository::findById).flatMap(Optional::stream)
            .filter(model->Boolean.TRUE.equals(model.getEnabled())).map(this::candidate).filter(Objects::nonNull).toList();
    }

    /** 返回功能路由候选模型和思考模式配置。 */
    public WorkerRoute resolve(String featureCode){
        LlmRoute route=routeRepository.findByFeatureCode(featureCode).filter(item->Boolean.TRUE.equals(item.getEnabled())).orElse(null);
        return new WorkerRoute(candidates(featureCode),route!=null&&Boolean.TRUE.equals(route.getEnableThinking()));
    }

    /** 调用 Worker 验证单个模型连接。 */
    public Map<String,Object> testModel(Long modelId){WorkerCandidate candidate=candidate(modelRepository.findById(modelId).orElseThrow(()->BusinessException.notFound("模型不存在")));if(candidate==null)throw new BusinessException("模型供应商不可用");Map<?,?> result=workerClient.post().uri("/llm/test").body(Map.of("candidate",candidate)).retrieve().body(Map.class);if(result==null)throw new BusinessException("Worker 返回空测试结果");Map<String,Object> response=new LinkedHashMap<>();result.forEach((key,value)->response.put(String.valueOf(key),value));return response;}

    private LlmProvider saveProvider(LlmProvider provider,ProviderCommand command){provider.setCode(require(command.code(),"请输入供应商编码"));provider.setName(require(command.name(),"请输入供应商名称"));provider.setBaseUrl(require(command.baseUrl(),"请输入服务地址").replaceAll("/+$",""));if(provider.getId()==null||!blank(command.apiKeys()))provider.setApiKeysEncrypted(cryptoService.encrypt(require(command.apiKeys(),"请输入 API Key")));provider.setConcurrencyLimit(positive(command.concurrencyLimit(),4));provider.setConcurrencyLevel(blank(command.concurrencyLevel())?"PROVIDER":command.concurrencyLevel().toUpperCase(Locale.ROOT));provider.setTimeoutSeconds(positive(command.timeoutSeconds(),60));provider.setEnabled(command.enabled()==null||command.enabled());return providerRepository.save(provider);}
    private LlmModel saveModel(LlmModel model,ModelCommand command){if(providerRepository.findById(command.providerId()).isEmpty())throw BusinessException.notFound("供应商不存在");model.setCode(require(command.code(),"请输入模型编码"));model.setName(require(command.name(),"请输入模型名称"));model.setProviderId(command.providerId());model.setModelName(require(command.modelName(),"请输入模型标识"));model.setModelType(blank(command.modelType())?"TEXT":command.modelType().toUpperCase(Locale.ROOT));model.setCapabilityLevel(blank(command.capabilityLevel())?"MIDDLE":command.capabilityLevel().toUpperCase(Locale.ROOT));model.setEnabled(command.enabled()==null||command.enabled());return modelRepository.save(model);}
    private LlmRoute saveRoute(LlmRoute route,RouteCommand command){List<Long> ids=command.candidateModelIds()==null?List.of():command.candidateModelIds();if(ids.isEmpty())throw new BusinessException("至少选择一个候选模型");if(modelRepository.findAllById(ids).size()!=ids.size())throw BusinessException.notFound("候选模型不存在");route.setFeatureCode(require(command.featureCode(),"请输入功能编码"));route.setName(require(command.name(),"请输入路由名称"));route.setCandidateModelIds(ids.stream().map(String::valueOf).reduce((a,b)->a+","+b).orElse(""));route.setEnableThinking(Boolean.TRUE.equals(command.enableThinking()));route.setEnabled(command.enabled()==null||command.enabled());return routeRepository.save(route);}
    private WorkerCandidate candidate(LlmModel model){LlmProvider provider=providerRepository.findById(model.getProviderId()).filter(item->Boolean.TRUE.equals(item.getEnabled())).orElse(null);if(provider==null)return null;List<String> keys=Arrays.stream(cryptoService.decrypt(provider.getApiKeysEncrypted()).split("[,\\n]")).map(String::trim).filter(value->!value.isBlank()).toList();return new WorkerCandidate(provider.getCode(),provider.getBaseUrl(),keys,model.getModelName(),provider.getConcurrencyLimit(),provider.getConcurrencyLevel(),provider.getTimeoutSeconds());}
    private ProviderView providerView(LlmProvider item){return new ProviderView(item.getId(),item.getCode(),item.getName(),item.getBaseUrl(),"******",item.getConcurrencyLimit(),item.getConcurrencyLevel(),item.getTimeoutSeconds(),item.getEnabled());}
    private RouteView routeView(LlmRoute item){return new RouteView(item.getId(),item.getFeatureCode(),item.getName(),parseIds(item.getCandidateModelIds()),item.getEnableThinking(),item.getEnabled());}
    private List<Long> parseIds(String value){if(blank(value))return List.of();return Arrays.stream(value.split(",")).map(String::trim).filter(v->!v.isBlank()).map(Long::valueOf).toList();}
    private int positive(Integer value,int fallback){return value==null||value<=0?fallback:value;} private boolean blank(String value){return value==null||value.isBlank();} private String require(String value,String message){if(blank(value))throw new BusinessException(message);return value.trim();}
    public record ProviderCommand(String code,String name,String baseUrl,String apiKeys,Integer concurrencyLimit,String concurrencyLevel,Integer timeoutSeconds,Boolean enabled){}
    public record ProviderView(Long id,String code,String name,String baseUrl,String apiKeys,Integer concurrencyLimit,String concurrencyLevel,Integer timeoutSeconds,Boolean enabled){}
    public record ModelCommand(String code,String name,Long providerId,String modelName,String modelType,String capabilityLevel,Boolean enabled){}
    public record RouteCommand(String featureCode,String name,List<Long> candidateModelIds,Boolean enableThinking,Boolean enabled){}
    public record RouteView(Long id,String featureCode,String name,List<Long> candidateModelIds,Boolean enableThinking,Boolean enabled){}
    public record WorkerCandidate(String providerCode,String baseUrl,List<String> apiKeys,String model,Integer concurrencyLimit,String concurrencyLevel,Integer timeoutSeconds){}
    public record WorkerRoute(List<WorkerCandidate> candidates,Boolean enableThinking){}
}
