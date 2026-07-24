package com.baseai.platform.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.*;
import com.baseai.platform.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.time.LocalDateTime;

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
    public static final String DEFAULT_ROUTE = "DEFAULT";
    /** 供应商数据访问接口 */
    private final LlmProviderRepository providerRepository;

    /** 模型数据访问接口 */
    private final LlmModelRepository modelRepository;

    /** 路由数据访问接口 */
    private final LlmRouteRepository routeRepository;

    /** 模型类型字典仓储，用于让新增类型无需修改业务代码。 */
    private final DictionaryDataRepository dictionaryDataRepository;

    /** 配置加密解密服务，用于API密钥的安全存储 */
    private final ConfigCryptoService cryptoService;

    /** Python Worker服务客户端，用于模型连接测试 */
    private final RestClient workerClient;
    /** 经健康检查后可参与真实调用的不可变路由快照。 */
    private final AtomicReference<Map<String, WorkerRoute>> activeRoutes = new AtomicReference<>(Map.of());

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
                                LlmRouteRepository routeRepository, DictionaryDataRepository dictionaryDataRepository,
                                ConfigCryptoService cryptoService,
                                @org.springframework.beans.factory.annotation.Qualifier("pythonWorkerRestClient") RestClient workerClient) {
        this.providerRepository=providerRepository;this.modelRepository=modelRepository;this.routeRepository=routeRepository;this.dictionaryDataRepository=dictionaryDataRepository;this.cryptoService=cryptoService;this.workerClient=workerClient;
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

    /** 查询启用的模型类型目录；字典尚未初始化时回退到内置的首批类型。 */
    public List<ModelTypeOption> modelTypes(){
        List<ModelTypeOption> result=dictionaryDataRepository.findByTypeCodeOrderBySortOrderAscIdAsc("llm_model_type").stream()
            .filter(item->Boolean.TRUE.equals(item.getEnabled()))
            .map(item->new ModelTypeOption(LlmModel.normalizeModelTypes(List.of(item.getDictValue())).stream().findFirst().orElse(""),item.getLabel()))
            .filter(item->!item.value().isBlank())
            .toList();
        return result.isEmpty()?List.of(new ModelTypeOption("text_model","文本模型"),new ModelTypeOption("vision_model","视觉模型")):result;
    }

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
    public void deleteRoute(Long id){LlmRoute route=routeRepository.findById(id).orElseThrow(()->BusinessException.notFound("能力路由不存在"));if(DEFAULT_ROUTE.equals(route.getFeatureCode()))throw new BusinessException("默认能力路由不可删除");routeRepository.deleteById(id);activeRoutes.updateAndGet(routes->{Map<String,WorkerRoute> copy=new LinkedHashMap<>(routes);copy.remove(route.getFeatureCode());return Map.copyOf(copy);});}

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
        LlmRoute route=routeRepository.findByFeatureCode(featureCode).filter(item->Boolean.TRUE.equals(item.getEnabled())).orElse(null);
        if(route==null)return List.of();
        return routeModels(route).stream()
            .map(model->candidate(model, Boolean.TRUE.equals(route.getEnableThinking())?route.getThinkingLevel():null))
            .filter(Objects::nonNull).toList();
    }

    /** 返回路由当前配置中可参与筛选的全部模型类型编码。 */
    public List<String> routeModelTypes(String featureCode){
        LlmRoute route=routeRepository.findByFeatureCode(featureCode).filter(item->Boolean.TRUE.equals(item.getEnabled())).orElse(null);
        if(route==null)return List.of();
        Set<Long> enabledProviders=providerRepository.findAll().stream().filter(item->Boolean.TRUE.equals(item.getEnabled()))
            .map(LlmProvider::getId).collect(java.util.stream.Collectors.toSet());
        String thinkingLevel=Boolean.TRUE.equals(route.getEnableThinking())?route.getThinkingLevel():null;
        return routeModels(route).stream().filter(model->enabledProviders.contains(model.getProviderId()))
            .filter(model->thinkingLevel==null||thinkingMappings(model.getThinkingLevels()).containsKey(thinkingLevel))
            .flatMap(model->model.getSupportedModelTypes().stream()).distinct().toList();
    }

    /** 根据候选模型或供应商池配置解析启用且健康的模型实体。 */
    private List<LlmModel> routeModels(LlmRoute route){
        List<LlmModel> models;
        if(!parseIds(route.getProviderIds()).isEmpty()){
            Set<Long> providers=new LinkedHashSet<>(parseIds(route.getProviderIds()));
            models=modelRepository.findAll().stream().filter(model->providers.contains(model.getProviderId()))
                .filter(model->Objects.equals(route.getCapabilityLevel(),model.getCapabilityLevel())).toList();
        }else models=parseIds(route.getCandidateModelIds()).stream().map(modelRepository::findById).flatMap(Optional::stream).toList();
        return models.stream().filter(model->Boolean.TRUE.equals(model.getEnabled()))
            .filter(model->!"FAILED".equals(model.getHealthStatus())).toList();
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
        return route==null?new WorkerRoute(List.of(),null,false):new WorkerRoute(candidates(featureCode),Boolean.TRUE.equals(route.getEnableThinking()),true);
    }

    /** 只从已同步的内存快照读取候选，避免保存中的数据库配置立即参与调用。 */
    public WorkerRoute resolveActive(String featureCode,String modelType){
        String code=blank(featureCode)||"chat".equalsIgnoreCase(featureCode)?DEFAULT_ROUTE:featureCode.trim();
        WorkerRoute route=activeRoutes.get().get(code);
        if(route!=null)route=new WorkerRoute(route.candidates().stream().filter(item->matchesModelType(item.supportedModelTypes(),modelType)).toList(),route.enableThinking(),true);
        if(route==null||route.candidates().isEmpty()){
            if(DEFAULT_ROUTE.equals(code))throw new BusinessException("默认模型池没有支持所选类型的可用模型，请检查能力路由并执行同步");
            throw new BusinessException("所选模型池没有支持该类型的可用模型，请检查能力路由并执行同步");
        }
        return route;
    }

    /** 确保默认路由存在，并保持其固定业务约束。 */
    @Transactional
    public void ensureDefaultRoute(){
        ensureDefaultRoute(DEFAULT_ROUTE,"默认能力路由");
    }
    private void ensureDefaultRoute(String code,String name){LlmRoute route=routeRepository.findByFeatureCode(code).orElseGet(()->{LlmRoute item=new LlmRoute();item.setFeatureCode(code);item.setName(name);item.setCandidateModelIds("");item.setProviderIds("");item.setCapabilityLevel("MIDDLE");item.setEnabled(true);return item;});route.setEnableThinking(false);route.setThinkingLevel(null);routeRepository.save(route);}

    /** 检查指定供应商池（为空时全部）并原子更新数据库状态和内存路由。 */
    @Transactional
    public synchronized List<ModelHealthView> syncRoutes(List<Long> providerIds){
        ensureDefaultRoute();Set<Long> selected=providerIds==null?Set.of():new LinkedHashSet<>(providerIds);
        List<LlmModel> checked=modelRepository.findAll().stream().filter(model->Boolean.TRUE.equals(model.getEnabled()))
            .filter(model->selected.isEmpty()||selected.contains(model.getProviderId())).toList();
        List<ModelHealthView> result=checkModels(checked);refreshActiveRoutes();return result;
    }

    /** 检查单条能力路由中的全部或指定供应商模型，并刷新全量内存快照。 */
    @Transactional
    public synchronized List<ModelHealthView> syncRoute(Long routeId,List<Long> providerIds){
        LlmRoute route=routeRepository.findById(routeId).orElseThrow(()->BusinessException.notFound("能力路由不存在"));
        Set<Long> selected=providerIds==null?Set.of():new LinkedHashSet<>(providerIds);
        Set<Long> routeProviders=routeProviderIds(route);
        List<LlmModel> checked=routeModelsForHealthCheck(route).stream()
            .filter(model->selected.isEmpty()||selected.contains(model.getProviderId()))
            .filter(model->routeProviders.isEmpty()||routeProviders.contains(model.getProviderId())).toList();
        List<ModelHealthView> result=checkModels(checked);refreshActiveRoutes();return result;
    }

    /** 按模型顺序执行健康检查并返回结果。 */
    private List<ModelHealthView> checkModels(List<LlmModel> models){
        List<ModelHealthView> result=new ArrayList<>();
        for(LlmModel model:models)result.add(checkModel(model));
        return result;
    }

    /** 根据路由配置解析健康检查范围，不受上次失败状态影响。 */
    private List<LlmModel> routeModelsForHealthCheck(LlmRoute route){
        List<Long> providerIds=parseIds(route.getProviderIds());
        if(!providerIds.isEmpty()){
            Set<Long> providers=new LinkedHashSet<>(providerIds);
            return modelRepository.findAll().stream().filter(model->providers.contains(model.getProviderId()))
                .filter(model->Boolean.TRUE.equals(model.getEnabled())).toList();
        }
        List<Long> candidateIds=parseIds(route.getCandidateModelIds());
        if(!candidateIds.isEmpty())return candidateIds.stream().map(modelRepository::findById).flatMap(Optional::stream)
            .filter(model->Boolean.TRUE.equals(model.getEnabled())).toList();
        return modelRepository.findAll().stream().filter(model->Boolean.TRUE.equals(model.getEnabled())).toList();
    }

    /** 返回当前路由显式关联的供应商集合。 */
    private Set<Long> routeProviderIds(LlmRoute route){
        Set<Long> providers=new LinkedHashSet<>(parseIds(route.getProviderIds()));
        if(!providers.isEmpty())return providers;
        parseIds(route.getCandidateModelIds()).stream().map(modelRepository::findById).flatMap(Optional::stream)
            .map(LlmModel::getProviderId).forEach(providers::add);
        return providers;
    }

    /** 使用数据库中的最新健康状态原子刷新全部内存路由。 */
    private void refreshActiveRoutes(){
        Map<String,WorkerRoute> next=new LinkedHashMap<>();
        for(LlmRoute route:routeRepository.findAll())if(Boolean.TRUE.equals(route.getEnabled())){
            List<WorkerCandidate> available=candidates(route.getFeatureCode());
            next.put(route.getFeatureCode(),new WorkerRoute(available,Boolean.TRUE.equals(route.getEnableThinking()),true));
        }
        activeRoutes.set(Map.copyOf(next));
    }

    /** 从当前路由移除供应商及其旧候选模型，并立即刷新内存快照。 */
    @Transactional
    public void removeProviderFromRoute(Long routeId,Long providerId){
        LlmRoute route=routeRepository.findById(routeId).orElseThrow(()->BusinessException.notFound("能力路由不存在"));
        List<Long> configuredProviders=parseIds(route.getProviderIds());
        List<Long> candidateModels=parseIds(route.getCandidateModelIds());
        if(configuredProviders.isEmpty()&&candidateModels.isEmpty())configuredProviders=providerRepository.findAll().stream().map(LlmProvider::getId).toList();
        List<Long> remainingProviders=configuredProviders.stream().filter(id->!id.equals(providerId)).toList();
        List<Long> remainingModels=candidateModels.stream()
            .filter(modelId->modelRepository.findById(modelId).map(model->!providerId.equals(model.getProviderId())).orElse(true)).toList();
        route.setProviderIds(joinIds(remainingProviders));
        route.setCandidateModelIds(joinIds(remainingModels));
        if(remainingProviders.isEmpty()&&remainingModels.isEmpty())route.setEnabled(false);
        routeRepository.save(route);refreshActiveRoutes();
    }

    /** 将ID集合保存为路由实体使用的逗号分隔格式。 */
    private String joinIds(List<Long> ids){return ids.stream().map(String::valueOf).reduce((left,right)->left+","+right).orElse("");}

    private ModelHealthView checkModel(LlmModel model){
        long started=System.nanoTime();String status;String error="";
        try{testModel(model.getId());long duration=(System.nanoTime()-started)/1_000_000;status=healthStatus(duration);model.setLastCheckDurationMs(duration);}catch(Exception exception){status="FAILED";error=exception.getMessage()==null?"模型检查失败":exception.getMessage();model.setLastCheckDurationMs(null);}
        model.setHealthStatus(status);model.setLastCheckError(error);model.setLastCheckedAt(LocalDateTime.now());modelRepository.save(model);return new ModelHealthView(model.getId(),model.getProviderId(),model.getName(),status,model.getLastCheckDurationMs(),error);
    }

    /** 按同步耗时划分健康状态，边界分别为10秒和30秒。 */
    static String healthStatus(long durationMs){return durationMs<10_000?"HEALTHY":durationMs<30_000?"WARNING":"SLOW";}

    /**
     * 按单个模型解析路由配置（单模型直连模式）。
     *
     * <p>不经过能力路由，直接以指定模型构建单元素候选池，用于对话页“单模型”模式。
     * 复用 {@link #candidate(LlmModel, String)}，与“测试模型”功能同源。</p>
     *
     * @param modelId 模型ID
     * @param enableThinking 是否启用思考
     * @param thinkingLevel 思考等级，仅在 enableThinking 为 true 时生效
     * @return 包含单个候选的 WorkerRoute，routeConfigured 恒为 true
     * @throws BusinessException 模型不存在、供应商停用，或开启思考但模型未配置所选等级
     */
    public WorkerRoute resolveModel(Long modelId,String modelType,boolean enableThinking,String thinkingLevel){
        LlmModel model=modelRepository.findById(modelId).orElseThrow(()->BusinessException.notFound("模型不存在"));
        if(!Boolean.TRUE.equals(model.getEnabled()))throw new BusinessException("所选模型已停用");
        if(!matchesModelType(model.getSupportedModelTypes(),modelType))throw new BusinessException("所选模型不支持该模型类型");
        String normalizedThinking=enableThinking&&!blank(thinkingLevel)?thinkingLevel.trim().toUpperCase(Locale.ROOT):null;
        WorkerCandidate candidate=candidate(model,normalizedThinking);
        if(candidate==null)throw new BusinessException(enableThinking?"所选模型未配置该思考等级":"所选模型的供应商不可用");
        return new WorkerRoute(List.of(candidate),enableThinking,true);
    }
    /** 兼容旧调用方，默认按文本模型解析单模型。 */
    public WorkerRoute resolveModel(Long modelId,boolean enableThinking,String thinkingLevel){return resolveModel(modelId,"text_model",enableThinking,thinkingLevel);}

    /**
     * 测试单个模型的连接可用性
     *
     * <p>通过调用Python Worker服务的测试接口验证模型配置是否正确、API密钥是否有效等</p>
     *
     * @param modelId 模型ID
     * @return 测试结果Map，包含Worker返回的测试详情
     * @throws BusinessException 如果模型不存在、供应商不可用或Worker返回空结果
     */
    public Map<String,Object> testModel(Long modelId){return testModel(modelId,null);}
    /** 使用可选思考等级测试模型，仅允许模型自身声明的等级参与调用。 */
    public Map<String,Object> testModel(Long modelId,String thinkingLevel){
        // 构建候选模型配置，包含解密后的API密钥
        String normalizedThinking=blank(thinkingLevel)?null:thinkingLevel.trim().toUpperCase(Locale.ROOT);
        WorkerCandidate candidate=candidate(modelRepository.findById(modelId).orElseThrow(()->BusinessException.notFound("模型不存在")),normalizedThinking);
        if(candidate==null)throw new BusinessException("模型供应商不可用");

        // 调用Worker的测试接口
        Map<String,Object> request=new LinkedHashMap<>();
        request.put("candidate",candidate);request.put("enableThinking",normalizedThinking!=null);
        Map<?,?> result=workerClient.post().uri("/llm/test").contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(Map.class);
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
        provider.setThinkingParameter(blank(command.thinkingParameter())?"reasoning_effort":command.thinkingParameter().trim());
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
        List<String> requestedTypes=command.supportedModelTypes()==null||command.supportedModelTypes().isEmpty()
            ?LlmModel.normalizeModelTypes(List.of(command.modelType()==null?"text_model":command.modelType()))
            :LlmModel.normalizeModelTypes(command.supportedModelTypes());
        if(requestedTypes.isEmpty())throw new BusinessException("请至少选择一个模型类型");
        Set<String> availableTypes=modelTypes().stream().map(ModelTypeOption::value).collect(java.util.stream.Collectors.toSet());
        if(!availableTypes.containsAll(requestedTypes))throw new BusinessException("模型类型不存在或已停用");
        model.setSupportedModelTypes(requestedTypes);
        model.setCapabilityLevel(blank(command.capabilityLevel())?"MIDDLE":command.capabilityLevel().toUpperCase(Locale.ROOT)); // 默认MIDDLE能力级别
        model.setThinkingLevels(normalizeThinkingLevels(command.thinkingLevels()));
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
        List<Long> providerIds=command.providerIds()==null?List.of():command.providerIds();
        boolean isDefault=DEFAULT_ROUTE.equals(route.getFeatureCode())||DEFAULT_ROUTE.equals(command.featureCode());
        if(ids.isEmpty()&&providerIds.isEmpty()&&!isDefault)throw new BusinessException("至少选择一个候选模型或供应商");

        // 验证所有候选模型是否都存在
        if(!ids.isEmpty()&&modelRepository.findAllById(ids).size()!=ids.size())
            throw BusinessException.notFound("候选模型不存在");
        if(!providerIds.isEmpty()&&providerRepository.findAllById(providerIds).size()!=providerIds.size())throw BusinessException.notFound("供应商不存在");

        route.setFeatureCode(isDefault?DEFAULT_ROUTE:require(command.featureCode(),"请输入功能编码"));
        route.setName(require(command.name(),"请输入路由名称"));

        // 将模型ID列表转换为逗号分隔的字符串
        route.setCandidateModelIds(ids.stream().map(String::valueOf).reduce((a,b)->a+","+b).orElse(""));
        route.setProviderIds(providerIds.stream().map(String::valueOf).reduce((a,b)->a+","+b).orElse(""));
        route.setEnableThinking(isDefault?false:Boolean.TRUE.equals(command.enableThinking()));
        route.setCapabilityLevel(isDefault||!providerIds.isEmpty()?require(command.capabilityLevel(),"请选择模型能力级别").toUpperCase(Locale.ROOT):null);
        route.setThinkingLevel(Boolean.TRUE.equals(route.getEnableThinking())?require(command.thinkingLevel(),"请选择思考级别").toUpperCase(Locale.ROOT):null);
        route.setEnabled(isDefault||command.enabled()==null||command.enabled());

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
    private WorkerCandidate candidate(LlmModel model){return candidate(model,null);}
    /** 根据路由所需思考等级构建候选；模型未配置该等级时不参与供应商池。 */
    private WorkerCandidate candidate(LlmModel model,String thinkingLevel){
        // 查找并验证供应商是否启用
        LlmProvider provider=providerRepository.findById(model.getProviderId())
            .filter(item->Boolean.TRUE.equals(item.getEnabled())).orElse(null);
        if(provider==null)return null;

        // 解密并解析API密钥列表（支持逗号或换行分隔）
        List<String> keys=Arrays.stream(cryptoService.decrypt(provider.getApiKeysEncrypted()).split("[,\\n]"))
            .map(String::trim)
            .filter(value->!value.isBlank())
            .toList();

        String thinkingValue=thinkingLevel==null?null:thinkingMappings(model.getThinkingLevels()).get(thinkingLevel);
        if(thinkingLevel!=null&&blank(thinkingValue))return null;
        return new WorkerCandidate(
            provider.getCode(),
            provider.getBaseUrl(),
            keys,
            model.getModelName(),
            provider.getConcurrencyLimit(),
            provider.getConcurrencyLevel(),
            provider.getTimeoutSeconds(), provider.getThinkingParameter(), thinkingValue, model.getSupportedModelTypes()
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
            item.getThinkingParameter(),
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
            parseIds(item.getProviderIds()), item.getCapabilityLevel(), item.getThinkingLevel(),
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
    /** 规范化并校验思考等级映射，避免未声明的等级参与路由匹配。 */
    private String normalizeThinkingLevels(String value){
        if(blank(value))return "";
        Map<String,String> mappings=thinkingMappings(value);
        return mappings.entrySet().stream().map(item->item.getKey()+"="+item.getValue()).reduce((a,b)->a+","+b).orElse("");
    }
    /** 解析逗号或换行分隔的“标准等级=供应商值”配置。 */
    private Map<String,String> thinkingMappings(String value){
        if(blank(value))return Map.of();
        Set<String> levels=Set.of("LOW","MEDIUM","HIGH","EXTRA_HIGH","MAX","ULTRA");
        Map<String,String> result=new LinkedHashMap<>();
        for(String item:value.split("[,\\n]")){String[] pair=item.split("=",2);if(pair.length==2&&!blank(pair[1])){String level=pair[0].trim().toUpperCase(Locale.ROOT);if(levels.contains(level))result.put(level,pair[1].trim());}}
        return result;
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
    /** 按任意字典类型编码筛选模型，不依赖固定枚举。 */
    private boolean matchesModelType(Collection<String> configured,String requested){
        String normalized=blank(requested)?"text_model":requested.trim().toLowerCase(Locale.ROOT);
        return LlmModel.normalizeModelTypes(configured).contains(normalized);
    }

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
    public record ProviderCommand(String code,String name,String baseUrl,String apiKeys,Integer concurrencyLimit,String concurrencyLevel,Integer timeoutSeconds,String thinkingParameter,Boolean enabled){}
    public record ProviderView(Long id,String code,String name,String baseUrl,String apiKeys,Integer concurrencyLimit,String concurrencyLevel,Integer timeoutSeconds,String thinkingParameter,Boolean enabled){}
    public record ProviderApiKeysView(Long id,String apiKeys){}
    public record ModelCommand(String code,String name,Long providerId,String modelName,List<String> supportedModelTypes,String modelType,String capabilityLevel,String thinkingLevels,Boolean enabled){
        /** 兼容旧版仅提交 modelType 的内部调用。 */
        public ModelCommand(String code,String name,Long providerId,String modelName,String modelType,String capabilityLevel,String thinkingLevels,Boolean enabled){this(code,name,providerId,modelName,null,modelType,capabilityLevel,thinkingLevels,enabled);}
    }
    public record RouteCommand(String featureCode,String name,List<Long> candidateModelIds,List<Long> providerIds,String capabilityLevel,Boolean enableThinking,String thinkingLevel,Boolean enabled){}
    public record RouteSyncCommand(Long routeId,List<Long> providerIds){}
    public record RouteView(Long id,String featureCode,String name,List<Long> candidateModelIds,List<Long> providerIds,String capabilityLevel,String thinkingLevel,Boolean enableThinking,Boolean enabled){}
    public record WorkerCandidate(String providerCode,String baseUrl,List<String> apiKeys,String model,Integer concurrencyLimit,String concurrencyLevel,Integer timeoutSeconds,String thinkingParameter,String thinkingValue,@JsonIgnore List<String> supportedModelTypes){}
    public record WorkerRoute(List<WorkerCandidate> candidates,Boolean enableThinking,boolean routeConfigured){}
    public record ModelHealthView(Long modelId,Long providerId,String modelName,String status,Long durationMs,String error){}
    public record ModelTypeOption(String value,String label){}
}
