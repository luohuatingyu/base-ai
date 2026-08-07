package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowTemplateCatalogTest {
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
        assertEquals("SYSTEM", WorkflowTemplateCatalog.source(null, true));
        assertEquals("CUSTOM", WorkflowTemplateCatalog.source(null, false));
        assertEquals("AI", WorkflowTemplateCatalog.category("", "LLM"));
        assertEquals("MESSAGE_QUEUE", WorkflowTemplateCatalog.category(null, "KAFKA_TRIGGER"));
    }

    /** 来源和分类接受大小写兼容输入但拒绝任意字符串。 */
    @Test
    void normalizesKnownMetadataAndRejectsUnknownValues() {
        assertEquals("CUSTOM", WorkflowTemplateCatalog.source(" custom ", false));
        assertEquals("DATA_STORAGE", WorkflowTemplateCatalog.category("data_storage", "HTTP"));
        assertEquals("workflow.templateSourceInvalid",
            assertThrows(BusinessException.class, () -> WorkflowTemplateCatalog.source("external", false)).getMessageKey());
        assertEquals("workflow.templateCategoryInvalid",
            assertThrows(BusinessException.class, () -> WorkflowTemplateCatalog.category("other", "LLM")).getMessageKey());
    }
}
