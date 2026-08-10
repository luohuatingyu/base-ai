package com.baseai.platform.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowModelCompatibilityTest {
    /** 聊天与向量节点必须使用明确且互斥的已知模型能力。 */
    @Test
    void assignsKnownModelCapabilitiesByExecutionProtocol() {
        assertEquals(List.of("text_model", "vision_model"), WorkflowModelCompatibility.allowedModelTypes("LLM"));
        assertEquals(List.of("embedding_model"), WorkflowModelCompatibility.allowedModelTypes("EMBEDDING"));
        assertEquals("CHAT", WorkflowModelCompatibility.policy("RAG").orElseThrow().protocol());
        assertEquals("EMBEDDINGS", WorkflowModelCompatibility.policy("EMBEDDING").orElseThrow().protocol());
        assertTrue(WorkflowModelCompatibility.supports("AGENT", "vision_model"));
        assertFalse(WorkflowModelCompatibility.supports("LLM", "embedding_model"));
        assertFalse(WorkflowModelCompatibility.supports("LLM", "audio_model"));
        assertTrue(WorkflowModelCompatibility.allowedModelTypes("HTTP").isEmpty());
    }

    /** 兼容目录必须覆盖全部实际调用模型的原生节点。 */
    @Test
    void coversEveryModelBackedNativeNode() {
        assertEquals(List.of("LLM", "AGENT", "RAG", "QUESTION_CLASSIFIER", "PARAMETER_EXTRACTOR", "EMBEDDING"),
            WorkflowModelCompatibility.policies().stream().map(WorkflowModelCompatibility.Policy::nodeType).toList());
    }
}
