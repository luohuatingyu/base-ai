package com.baseai.platform.service;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.*;
import com.baseai.platform.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * LLM模型管理服务
 *
 * <p>该服务负责管理大语言模型（LLM）相关的三层配置结构：</p>
 * <ul>
 *   <li>供应商（Provider）：管理模型供应商的基本信息、API密钥、并发限制等配置</li>
 *   <li>模型（Model）：管理具体的模型配置，关联到供应商</li>
 *   <li>能力路由（Route）：将业务功能映射到候选模型列表，支持思考模式配置</li>
 * </ul>
 *
 * <p>主要功能：</p>
 * <ul>
 *   <li>供应商管理：创建、更新、删除供应商，API密钥加密存储</li>
 *   <li>模型管理：创建、更新、删除模型配置</li>
 *   <li>路由管理：配置功能到模型的映射关系</li>
 *   <li>模型解析：根据功能代码解析可用的候选模型列表</li>
 *   <li>连接测试：验证模型配置的可用性</li>
 * </ul>
 *
 * @author BaseAI Platform
 * @since 1.0
 */
@Service
public class LlmManagementService {
    /** 供应商数据访问接口 */
    private final LlmProviderRepository providerRepository;

    /** 模型数据访问接口 */
    private final LlmModelRepository modelRepository;

    /** 路由数据访问接口 */
    private final LlmRouteRepository routeRepository;

    /** 配置加密解密服务，用于API密钥的安全存储 */
    private final ConfigCryptoService cryptoService;

    /** Python Worker服务客户端，用于模型连接测试 */
    private final RestClient workerClient;

    /**
     * 构造函数，注入所需的依赖
     *
     * @param providerRepository 供应商仓储
     * @param modelRepository 模型仓储
     * @param routeRepository 路由仓储
     * @param cryptoService 加密服务
     * @param workerClient Python Worker REST客户端（通过pythonWorkerRestClient限定符注入）
     */
    public LlmManagementService(LlmProviderRepository providerRepository, LlmModelRepository modelRepository,
                                LlmRouteRepository routeRepository, ConfigCryptoService cryptoService,
                                @org.springframework.beans.factory.annotation.Qualifier("pythonWorkerRestClient") RestClient workerClient) {
        this.providerRepository=providerRepository;this.modelRepository=modelRepository;this.routeRepository=routeRepository;this.cryptoService=cryptoService;this.workerClient=workerClient;
    }

    /**
     * 查询所有供应商列表
     *
     * <p>返回的供应商视图对象会将API密钥进行脱敏处理，显示为"******"</p>
     *
     * @return 供应商视图列表，按ID升序排列
     */
    public List<ProviderView> providers(){return providerRepository.findAll().stream().sorted(Comparator.comparing(LlmProvider::getId)).map(this::providerView).toList();}

    /**
     * 查询单个供应商的明文 API Key，并统一格式为一行一个密钥。
     *
     * <p>此方法只应由受更新权限保护的接口调用，列表接口仍不得返回明文。</p>
     *
     * @param id 供应商ID
     * @return 含明文密钥的供应商密钥视图
     */
    public ProviderApiKeysView providerApiKeys(Long id){
        LlmProvider provider=providerRepository.findById(id).orElseThrow(()->BusinessException.notFound("供应商不存在"));
        return new ProviderApiKeysView(provider.getId(),normalizeApiKeys(cryptoService.decrypt(provider.getApiKeysEncrypted())));
    }

    /**
     * 创建新的模型供应商
     *
     * @param command 供应商创建命令，包含编码、名称、服务地址、API密钥等信息
     * @return 创建成功的供应商视图对象
     * @throws BusinessException 如果供应商编码已存在
     */
    @Transactional
    public ProviderView createProvider(ProviderCommand command){if(providerRepository.findByCode(require(command.code(),"请输入供应商编码")).isPresent())throw new BusinessException("供应商编码已存在");return providerView(saveProvider(new LlmProvider(),command));}

    /**
     * 更新已有的模型供应商
     *
     * @param id 供应商ID
     * @param command 供应商更新命令
     * @return 更新后的供应商视图对象
     * @throws BusinessException 如果供应商不存在
     */
    @Transactional
    public ProviderView updateProvider(Long id,ProviderCommand command){return providerView(saveProvider(providerRepository.findById(id).orElseThrow(()->BusinessException.notFound("供应商不存在")),command));}

    /**
     * 删除模型供应商
     *
     * <p>仅当供应商未被任何模型使用时才允许删除</p>
     *
     * @param id 供应商ID
     * @throws BusinessException 如果供应商已被模型使用
     */
    @Transactional
    public void deleteProvider(Long id){if(modelRepository.findAll().stream().anyMatch(item->id.equals(item.getProviderId())))throw new BusinessException("供应商已被模型使用");providerRepository.deleteById(id);}

    /**
     * 查询所有模型配置列表
     *
     * @return 模型配置列表，按ID升序排列
     */
    public List<LlmModel> models(){return modelRepository.findAll().stream().sorted(Comparator.comparing(LlmModel::getId)).toList();}

    /**
     * 创建新的模型配置
     *
     * @param command 模型创建命令，包含编码、名称、供应商ID、模型标识等信息
     * @return 创建成功的模型对象
     * @throws BusinessException 如果模型编码已存在
     */
    @Transactional
    public LlmModel createModel(ModelCommand command){if(modelRepository.findByCode(require(command.code(),"请输入模型编码")).isPresent())throw new BusinessException("模型编码已存在");return saveModel(new LlmModel(),command);}

    /**
     * 更新已有的模型配置
     *
     * @param id 模型ID
     * @param command 模型更新命令
     * @return 更新后的模型对象
     * @throws BusinessException 如果模型不存在
     */
    @Transactional
    public LlmModel updateModel(Long id,ModelCommand command){return saveModel(modelRepository.findById(id).orElseThrow(()->BusinessException.notFound("模型不存在")),command);}

    /**
     * 删除模型配置
     *
     * <p>仅当模型未被任何能力路由引用时才允许删除</p>
     *
     * @param id 模型ID
     * @throws BusinessException 如果模型已被能力路由使用
     */
    @Transactional
    public void deleteModel(Long id){if(routeRepository.findAll().stream().anyMatch(route->parseIds(route.getCandidateModelIds()).contains(id)))throw new BusinessException("模型已被能力路由使用");modelRepository.deleteById(id);}

    /**
     * 查询所有能力路由配置列表
     *
     * @return 路由视图列表，按功能编码排序
     */
    public List<RouteView> routes(){return routeRepository.findAll().stream().sorted(Comparator.comparing(LlmRoute::getFeatureCode)).map(this::routeView).toList();}

    /**
     * 创建新的能力路由配置
     *
     * @param command 路由创建命令，包含功能编码、名称、候选模型ID列表等信息
     * @return 创建成功的路由视图对象
     * @throws BusinessException 如果功能编码已存在
     */
    @Transactional
    public RouteView createRoute(RouteCommand command){if(routeRepository.findByFeatureCode(require(command.featureCode(),"请输入功能编码")).isPresent())throw new BusinessException("功能编码已存在");return routeView(saveRoute(new LlmRoute(),command));}

    /**
     * 更新已有的能力路由配置
     *
     * @param id 路由ID
     * @param command 路由更新命令
     * @return 更新后的路由视图对象
     * @throws BusinessException 如果路由不存在
     */
    @Transactional
    public RouteView updateRoute(Long id,RouteCommand command){return routeView(saveRoute(routeRepository.findById(id).orElseThrow(()->BusinessException.notFound("能力路由不存在")),command));}

    /**
     * 删除能力路由配置
     *
     * @param id 路由ID
     */
    public void deleteRoute(Long id){routeRepository.deleteById(id);}

    /**
     * 解析指定功能的候选模型列表
     *
     * <p>该方法会执行以下过滤逻辑：</p>
     * <ol>
     *   <li>查找功能编码对应的已启用路由配置</li>
     *   <li>解析路由中配置的候选模型ID列表</li>
     *   <li>过滤出已启用的模型</li>
     *   <li>验证模型对应的供应商也已启用</li>
     *   <li>构建WorkerCandidate对象，包含解密后的API密钥</li>
     * </ol>
     *
     * @param featureCode 功能编码
     * @return 可用的候选模型列表，如果路由未配置或被禁用则返回空列表
     */
    public List<WorkerCandidate> candidates(String featureCode){
        return routeRepository.findByFeatureCode(featureCode).filter(route->Boolean.TRUE.equals(route.getEnabled())).stream()
            .flatMap(route->parseIds(route.getCandidateModelIds()).stream()).map(modelRepository::findById).flatMap(Optional::stream)
            .filter(model->Boolean.TRUE.equals(model.getEnabled())).map(this::candidate).filter(Objects::nonNull).toList();
    }

    /**
     * 解析功能路由配置
     *
     * <p>返回指定功能的完整路由配置，包括候选模型列表和思考模式开关</p>
     *
     * @param featureCode 功能编码
     * @return WorkerRoute对象，包含候选模型列表和思考模式配置。如果路由不存在或未启用，返回空候选列表和null思考模式
     */
    public WorkerRoute resolve(String featureCode){
        LlmRoute route=routeRepository.findByFeatureCode(featureCode).filter(item->Boolean.TRUE.equals(item.getEnabled())).orElse(null);
        return route==null?new WorkerRoute(List.of(),null):new WorkerRoute(candidates(featureCode),Boolean.TRUE.equals(route.getEnableThinking()));
    }

    /**
     * 测试单个模型的连接可用性
     *
     * <p>通过调用Python Worker服务的测试接口验证模型配置是否正确、API密钥是否有效等</p>
     *
     * @param modelId 模型ID
     * @return 测试结果Map，包含Worker返回的测试详情
     * @throws BusinessException 如果模型不存在、供应商不可用或Worker返回空结果
     */
    public Map<String,Object> testModel(Long modelId){
        // 构建候选模型配置，包含解密后的API密钥
        WorkerCandidate candidate=candidate(modelRepository.findById(modelId).orElseThrow(()->BusinessException.notFound("模型不存在")));
        if(candidate==null)throw new BusinessException("模型供应商不可用");

        // 调用Worker的测试接口
        Map<?,?> result=workerClient.post().uri("/llm/test").contentType(MediaType.APPLICATION_JSON).body(Map.of("candidate",candidate)).retrieve().body(Map.class);
        if(result==null)throw new BusinessException("Worker 返回空测试结果");

        // 转换结果为String类型的Map
        Map<String,Object> response=new LinkedHashMap<>();
        result.forEach((key,value)->response.put(String.valueOf(key),value));
        return response;
    }

    /**
     * 保存供应商信息（内部方法）
     *
     * <p>处理供应商的创建和更新逻辑，包括：</p>
     * <ul>
     *   <li>验证必填字段</li>
     *   <li>规范化服务地址（去除尾部斜杠）</li>
     *   <li>加密存储API密钥（仅在新建或明确提供新密钥时更新）</li>
     *   <li>设置并发限制和超时时间的默认值</li>
     * </ul>
     *
     * @param provider 供应商实体对象（新建或已存在）
     * @param command 供应商命令对象
     * @return 保存后的供应商实体
     * @throws BusinessException 如果必填字段缺失
     */
    private LlmProvider saveProvider(LlmProvider provider,ProviderCommand command){
        provider.setCode(require(command.code(),"请输入供应商编码"));
        provider.setName(require(command.name(),"请输入供应商名称"));
        provider.setBaseUrl(require(command.baseUrl(),"请输入服务地址").replaceAll("/+$","")); // 去除末尾的斜杠

        // 仅在新建或提供了新密钥时才更新加密的API密钥
        if(provider.getId()==null||!blank(command.apiKeys()))
            provider.setApiKeysEncrypted(cryptoService.encrypt(require(command.apiKeys(),"请输入 API Key")));

        provider.setConcurrencyLimit(positive(command.concurrencyLimit(),4)); // 默认并发限制为4
        provider.setConcurrencyLevel(blank(command.concurrencyLevel())?"PROVIDER":command.concurrencyLevel().toUpperCase(Locale.ROOT));
        provider.setTimeoutSeconds(positive(command.timeoutSeconds(),60)); // 默认超时60秒
        provider.setEnabled(command.enabled()==null||command.enabled()); // 默认启用

        return providerRepository.save(provider);
    }
    /**
     * 保存模型信息（内部方法）
     *
     * <p>处理模型的创建和更新逻辑，包括：</p>
     * <ul>
     *   <li>验证供应商是否存在</li>
     *   <li>验证必填字段</li>
     *   <li>设置模型类型和能力级别的默认值</li>
     * </ul>
     *
     * @param model 模型实体对象（新建或已存在）
     * @param command 模型命令对象
     * @return 保存后的模型实体
     * @throws BusinessException 如果供应商不存在或必填字段缺失
     */
    private LlmModel saveModel(LlmModel model,ModelCommand command){
        // 验证供应商是否存在
        if(providerRepository.findById(command.providerId()).isEmpty())
            throw BusinessException.notFound("供应商不存在");

        model.setCode(require(command.code(),"请输入模型编码"));
        model.setName(require(command.name(),"请输入模型名称"));
        model.setProviderId(command.providerId());
        model.setModelName(require(command.modelName(),"请输入模型标识"));
        model.setModelType(blank(command.modelType())?"TEXT":command.modelType().toUpperCase(Locale.ROOT)); // 默认TEXT类型
        model.setCapabilityLevel(blank(command.capabilityLevel())?"MIDDLE":command.capabilityLevel().toUpperCase(Locale.ROOT)); // 默认MIDDLE能力级别
        model.setEnabled(command.enabled()==null||command.enabled()); // 默认启用

        return modelRepository.save(model);
    }
    /**
     * 保存路由信息（内部方法）
     *
     * <p>处理路由的创建和更新逻辑，包括：</p>
     * <ul>
     *   <li>验证至少选择了一个候选模型</li>
     *   <li>验证所有候选模型都存在</li>
     *   <li>将模型ID列表转换为逗号分隔的字符串存储</li>
     * </ul>
     *
     * @param route 路由实体对象（新建或已存在）
     * @param command 路由命令对象
     * @return 保存后的路由实体
     * @throws BusinessException 如果候选模型列表为空或模型不存在
     */
    private LlmRoute saveRoute(LlmRoute route,RouteCommand command){
        List<Long> ids=command.candidateModelIds()==null?List.of():command.candidateModelIds();
        if(ids.isEmpty())throw new BusinessException("至少选择一个候选模型");

        // 验证所有候选模型是否都存在
        if(modelRepository.findAllById(ids).size()!=ids.size())
            throw BusinessException.notFound("候选模型不存在");

        route.setFeatureCode(require(command.featureCode(),"请输入功能编码"));
        route.setName(require(command.name(),"请输入路由名称"));

        // 将模型ID列表转换为逗号分隔的字符串
        route.setCandidateModelIds(ids.stream().map(String::valueOf).reduce((a,b)->a+","+b).orElse(""));
        route.setEnableThinking(Boolean.TRUE.equals(command.enableThinking()));
        route.setEnabled(command.enabled()==null||command.enabled()); // 默认启用

        return routeRepository.save(route);
    }
    /**
     * 构建Worker候选模型对象（内部方法）
     *
     * <p>将模型和供应商配置转换为Worker服务所需的候选模型格式，包括：</p>
     * <ul>
     *   <li>解密供应商的API密钥</li>
     *   <li>解析多个API密钥（支持逗号或换行分隔）</li>
     *   <li>验证供应商是否启用</li>
     * </ul>
     *
     * @param model 模型实体对象
     * @return WorkerCandidate对象，如果供应商不存在或未启用则返回null
     */
    private WorkerCandidate candidate(LlmModel model){
        // 查找并验证供应商是否启用
        LlmProvider provider=providerRepository.findById(model.getProviderId())
            .filter(item->Boolean.TRUE.equals(item.getEnabled())).orElse(null);
        if(provider==null)return null;

        // 解密并解析API密钥列表（支持逗号或换行分隔）
        List<String> keys=Arrays.stream(cryptoService.decrypt(provider.getApiKeysEncrypted()).split("[,\\n]"))
            .map(String::trim)
            .filter(value->!value.isBlank())
            .toList();

        return new WorkerCandidate(
            provider.getCode(),
            provider.getBaseUrl(),
            keys,
            model.getModelName(),
            provider.getConcurrencyLimit(),
            provider.getConcurrencyLevel(),
            provider.getTimeoutSeconds()
        );
    }
    /**
     * 构建供应商视图对象（内部方法）
     *
     * <p>将供应商实体转换为视图对象，API密钥会被脱敏显示为"******"</p>
     *
     * @param item 供应商实体对象
     * @return 供应商视图对象
     */
    private ProviderView providerView(LlmProvider item){
        return new ProviderView(
            item.getId(),
            item.getCode(),
            item.getName(),
            item.getBaseUrl(),
            "******", // API密钥脱敏
            item.getConcurrencyLimit(),
            item.getConcurrencyLevel(),
            item.getTimeoutSeconds(),
            item.getEnabled()
        );
    }

    /**
     * 构建路由视图对象（内部方法）
     *
     * <p>将路由实体转换为视图对象，将存储的逗号分隔的模型ID字符串解析为ID列表</p>
     *
     * @param item 路由实体对象
     * @return 路由视图对象
     */
    private RouteView routeView(LlmRoute item){
        return new RouteView(
            item.getId(),
            item.getFeatureCode(),
            item.getName(),
            parseIds(item.getCandidateModelIds()), // 解析模型ID列表
            item.getEnableThinking(),
            item.getEnabled()
        );
    }

    /**
     * 解析逗号分隔的ID字符串为Long列表（内部方法）
     *
     * @param value 逗号分隔的ID字符串
     * @return ID列表，如果输入为空则返回空列表
     */
    private List<Long> parseIds(String value){
        if(blank(value))return List.of();
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(v->!v.isBlank())
            .map(Long::valueOf)
            .toList();
    }
    /**
     * 验证正整数，提供默认值（工具方法）
     *
     * @param value 待验证的值
     * @param fallback 默认值
     * @return 如果value为null或小于等于0，返回fallback，否则返回value
     */
    private int positive(Integer value,int fallback){return value==null||value<=0?fallback:value;}

    /**
     * 判断字符串是否为空（工具方法）
     *
     * @param value 待判断的字符串
     * @return 如果字符串为null或空白，返回true，否则返回false
     */
    private boolean blank(String value){return value==null||value.isBlank();}

    /** 将逗号或换行分隔的密钥标准化为每行一个密钥。 */
    private String normalizeApiKeys(String value){
        return Arrays.stream(value.split("[,\n]"))
            .map(String::trim)
            .filter(key->!key.isBlank())
            .reduce((first,second)->first+"\n"+second)
            .orElse("");
    }

    /**
     * 验证必填字符串（工具方法）
     *
     * @param value 待验证的字符串
     * @param message 错误提示消息
     * @return 去除首尾空格后的字符串
     * @throws BusinessException 如果字符串为空
     */
    private String require(String value,String message){
        if(blank(value))throw new BusinessException(message);
        return value.trim();
    }
    public record ProviderCommand(String code,String name,String baseUrl,String apiKeys,Integer concurrencyLimit,String concurrencyLevel,Integer timeoutSeconds,Boolean enabled){}
    public record ProviderView(Long id,String code,String name,String baseUrl,String apiKeys,Integer concurrencyLimit,String concurrencyLevel,Integer timeoutSeconds,Boolean enabled){}
    public record ProviderApiKeysView(Long id,String apiKeys){}
    public record ModelCommand(String code,String name,Long providerId,String modelName,String modelType,String capabilityLevel,Boolean enabled){}
    public record RouteCommand(String featureCode,String name,List<Long> candidateModelIds,Boolean enableThinking,Boolean enabled){}
    public record RouteView(Long id,String featureCode,String name,List<Long> candidateModelIds,Boolean enableThinking,Boolean enabled){}
    public record WorkerCandidate(String providerCode,String baseUrl,List<String> apiKeys,String model,Integer concurrencyLimit,String concurrencyLevel,Integer timeoutSeconds){}
    public record WorkerRoute(List<WorkerCandidate> candidates,Boolean enableThinking){}
}
