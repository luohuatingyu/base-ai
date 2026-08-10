package com.baseai.platform.workflow;

import com.baseai.platform.knowledge.KnowledgeBaseService;
import com.baseai.platform.service.AiChatClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowRagNodeExecutorTest {
    /** RAG 必须按工作流所有者检索，并把片段作为受限上下文生成引用回答。 */
    @Test void retrievesForWorkflowOwnerAndReturnsCitations(){ObjectMapper mapper=new ObjectMapper();KnowledgeBaseService knowledge=mock(KnowledgeBaseService.class);AiChatClient chat=mock(AiChatClient.class);
        when(knowledge.retrieve(9L,"question",3,0.2,7L)).thenReturn(new KnowledgeBaseService.Retrieval(9L,"Docs",List.of(new KnowledgeBaseService.RetrievedChunk(11L,12L,"guide.txt","trusted fact",0.91))));
        when(chat.chat(anyString(),anyString(),anyList(),anyDouble(),any(),any(),any())).thenReturn(new AiChatClient.ChatResult("answer [1]","text-model",10,4,14));
        WorkflowRagNodeExecutor executor=new WorkflowRagNodeExecutor(mapper,new WorkflowExpressionService(mapper),knowledge,chat);ObjectNode config=mapper.createObjectNode().put("knowledgeBaseId",9).put("query","question").put("topK",3).put("scoreThreshold",0.2).put("modelMode","ROUTE").put("featureCode","DEFAULT").put("modelType","text_model");
        WorkflowNodeExecutor.Result result=executor.execute(new WorkflowNodeExecutor.Request("run","rag","RAG",config,mapper.createObjectNode(),7L));assertEquals("answer [1]",result.output().path("answer").asText());assertEquals("guide.txt",result.output().path("citations").get(0).path("fileName").asText());
        ArgumentCaptor<List<AiChatClient.Message>> messages=ArgumentCaptor.forClass(List.class);verify(chat).chat(eq("DEFAULT"),eq("text_model"),messages.capture(),eq(0D),isNull(),isNull(),isNull());assertTrue(messages.getValue().get(1).content().toString().contains("trusted fact"));
    }
}
