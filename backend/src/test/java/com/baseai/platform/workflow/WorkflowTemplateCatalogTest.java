package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowTemplateCatalogTest {
    /** 市场插件必须优先依据技术类型和实际能力语义选择功能分类。 */
    @ParameterizedTest
    @MethodSource("marketplaceCategoryCases")
    void categorizesMarketplaceComponentsByActualCapability(String componentType, String expected,
                                                             String[] metadata) {
        assertEquals(expected, WorkflowTemplateCatalog.marketplaceCategory(componentType, metadata));
    }

    /** 提供技术类型、常见市场能力和未知能力的分类样本。 */
    private static Stream<Arguments> marketplaceCategoryCases() {
        return Stream.of(
            Arguments.of("TRIGGER", "TRIGGER", new String[]{"Generic event"}),
            Arguments.of("MODEL", "AI", new String[]{"Unbranded model"}),
            Arguments.of("AGENT_STRATEGY", "AI", new String[]{"Planner"}),
            Arguments.of("DATASOURCE", "DATA_STORAGE", new String[]{"Generic source"}),
            Arguments.of("ACTION", "NOTIFICATION", new String[]{"Slack", "Send channel messages"}),
            Arguments.of("TOOL", "MESSAGE_QUEUE", new String[]{"RabbitMQ publisher"}),
            Arguments.of("ACTION", "DATA_STORAGE", new String[]{"PostgreSQL database query"}),
            Arguments.of("TOOL", "TEXT_DOCUMENT", new String[]{"PDF document extractor"}),
            Arguments.of("EXTENSION", "DATA_TRANSFORM", new String[]{"JSON to YAML converter"}),
            Arguments.of("TOOL", "AI", new String[]{"OpenAI embedding utility"}),
            Arguments.of("ACTION", "NETWORK_API", new String[]{"Tavily web search"}),
            Arguments.of("ACTION", "BASIC", new String[]{"", "Unclassified productivity helper"}),
            Arguments.of(null, "BASIC", new String[]{null, ""})
        );
    }

    /** 每个可执行原生节点必须拥有受控且可展示的默认功能分类。 */
    @Test
    void assignsEveryNativeNodeToKnownCategory() {
        for (String nodeType : WorkflowNodeTypes.ALL) {
            assertTrue(WorkflowTemplateCatalog.CATEGORIES.contains(WorkflowTemplateCatalog.defaultCategory(nodeType)), nodeType);
        }
    }

    /** 旧客户端省略元数据时保持系统来源并按节点类型推导分类。 */
    @Test
    void defaultsMissingMetadataForBackwardCompatibility() {
        assertEquals("SYSTEM", WorkflowTemplateCatalog.source(null));
        assertEquals("AI", WorkflowTemplateCatalog.category("", "LLM"));
        assertEquals("MESSAGE_QUEUE", WorkflowTemplateCatalog.category(null, "KAFKA_TRIGGER"));
        assertEquals("N8N", WorkflowTemplateCatalog.updatedSource(null, "N8N"));
        assertEquals("TRIGGER", WorkflowTemplateCatalog.updatedCategory("", "TRIGGER", "HTTP"));
    }

    /** 来源和分类接受大小写兼容输入但拒绝任意字符串。 */
    @Test
    void normalizesKnownMetadataAndRejectsUnknownValues() {
        assertEquals("DIFY", WorkflowTemplateCatalog.source(" dify "));
        assertEquals("DATA_STORAGE", WorkflowTemplateCatalog.category("data_storage", "HTTP"));
        assertEquals("workflow.templateSourceInvalid",
            assertThrows(BusinessException.class, () -> WorkflowTemplateCatalog.source("external")).getMessageKey());
        assertEquals("workflow.templateCategoryInvalid",
            assertThrows(BusinessException.class, () -> WorkflowTemplateCatalog.category("other", "LLM")).getMessageKey());
    }
}
