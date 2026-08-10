package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.service.AiChatClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 使用平台向量协议把单个文本或文本数组转换为顺序稳定的向量。 */
@Component
public class WorkflowEmbeddingNodeExecutor implements WorkflowNodeExecutor {
    private final ObjectMapper objectMapper;
    private final WorkflowExpressionService expressions;
    private final AiChatClient aiChatClient;

    /** 注入表达式解析、JSON 和统一模型客户端。 */
    public WorkflowEmbeddingNodeExecutor(ObjectMapper objectMapper,WorkflowExpressionService expressions,AiChatClient aiChatClient){
        this.objectMapper=objectMapper;this.expressions=expressions;this.aiChatClient=aiChatClient;
    }

    /** 向执行器注册表声明唯一的原生节点类型。 */
    @Override public Set<String> types(){return Set.of("EMBEDDING");}

    /** 解析模型来源和文本输入，调用向量协议并返回统一批量结构。 */
    @Override public Result execute(Request request){
        ObjectNode config=WorkflowNodeConfigDefaults.withDefaults(objectMapper,request.type(),
            expressions.resolve(request.config(),request.context()));
        WorkflowNodeConfigValidator.validateResolved(request.type(),config);
        List<String> input=normalizedInput(config.path("input"));
        Long modelId=config.path("modelMode").asText().equalsIgnoreCase("DIRECT")?config.path("modelId").asLong():null;
        AiChatClient.EmbeddingResult result=aiChatClient.embed(config.path("featureCode").asText("DEFAULT"),modelId,input);
        if(result.embeddings()==null||result.embeddings().isEmpty())throw new BusinessException("knowledge.embeddingResponseInvalid");
        int dimension=result.embeddings().get(0).size();
        ObjectNode output=objectMapper.createObjectNode().put("model",result.model()).put("count",result.embeddings().size()).put("dimension",dimension);
        ArrayNode embeddings=output.putArray("embeddings");result.embeddings().forEach(vector->embeddings.add(objectMapper.valueToTree(vector)));
        return Result.output(output);
    }

    /** 将单文本规范为一项数组，并再次限制批量和单项字符边界。 */
    private List<String> normalizedInput(JsonNode value){
        List<String> result=new ArrayList<>();
        if(value.isTextual())result.add(value.asText().trim());
        else if(value.isArray())value.forEach(item->{if(item.isTextual())result.add(item.asText().trim());else result.add("");});
        if(result.isEmpty()||result.size()>256||result.stream().anyMatch(item->item.isBlank()||item.length()>500))
            throw new BusinessException("knowledge.embeddingInputInvalid");
        return List.copyOf(result);
    }
}
