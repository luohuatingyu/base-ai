package com.baseai.platform.modeltype;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LlmModelTypeRegistryTest {
    private final LlmModelTypeRegistry registry = new LlmModelTypeRegistry();

    @Test
    void resolvesIndependentProtocols() {
        assertTrue(registry.supports("text_model", LlmOperation.CHAT));
        assertTrue(registry.supports("vision_model", LlmOperation.CHAT));
        assertTrue(registry.supports("embedding_model", LlmOperation.EMBEDDING));
        assertFalse(registry.supports("embedding_model", LlmOperation.CHAT));
        assertEquals("/images/generations", registry.generationPath("image_model"));
        assertEquals("/videos/generations", registry.generationPath("video_model"));
    }

    @Test
    void keepsUnknownDictionaryTypesChatCompatible() {
        assertTrue(registry.supports("audio_model", LlmOperation.CHAT));
        assertEquals("chat", registry.healthProtocol(List.of("audio_model")));
    }

    @Test
    void normalizesMixedTypesAndDoesNotExposeGenerationAsChat() {
        assertEquals(List.of("text_model", "image_model", "embedding_model"),
            registry.normalize(List.of("TEXT_MODEL,image_model", "embedding_model", "text_model")));
        assertEquals(List.of("text_model"), registry.chatTypes(List.of("image_model", "text_model")));
        assertTrue(registry.containsGeneration(List.of("text_model", "video_model")));
        assertEquals("embedding", registry.healthProtocol(List.of("embedding_model")));
    }

    @Test
    void allowsCustomHandlerWithoutChangingRegistryCore() {
        var custom = new BasicLlmModelTypeHandler("audio_model", Set.of(LlmOperation.EMBEDDING, LlmOperation.HEALTH_CHECK), "embedding", null);
        var customRegistry = new LlmModelTypeRegistry(List.of(custom));
        assertTrue(customRegistry.supports("audio_model", LlmOperation.EMBEDDING));
        assertFalse(customRegistry.supports("audio_model", LlmOperation.CHAT));
    }
}
