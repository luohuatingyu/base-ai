package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowMarketplaceClientsTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowMarketplaceClients clients = new WorkflowMarketplaceClients(objectMapper, new PlatformProperties());

    /** 录制的 n8n 官方字段必须解析为稳定外部 ID 与名称。 */
    @Test
    void parsesRecordedN8nCatalogContract() throws Exception {
        var result = clients.parseN8n(objectMapper.readTree("""
            [{"id":"n8n-nodes-base.postgres","label":"Postgres"}]
            """));

        assertEquals(1, result.total());
        assertEquals("n8n-nodes-base.postgres", result.items().get(0).externalId());
        assertEquals("Postgres", result.items().get(0).name());
    }

    /** 录制的 Dify 插件字段必须保留版本、发布组织与插件级总数。 */
    @Test
    void parsesRecordedDifyCatalogContract() throws Exception {
        var result = clients.parseDify(objectMapper.readTree("""
            {"data":{"plugins":[{"plugin_id":"langgenius/tavily","name":"tavily",
              "label":{"en_US":"Tavily"},"brief":{"en_US":"Search"},"latest_version":"0.1.11",
              "org":"langgenius","category":"tool","verification":{"authorized_category":"official"}}],"total":9}}
            """));

        assertEquals(9, result.total());
        assertEquals("0.1.11", result.items().get(0).version());
        assertEquals("langgenius", result.items().get(0).publisher());
        assertEquals("official", result.items().get(0).trustLevel());
    }

    /** 外部响应缺少约定数组时必须失败，不能返回误导性空市场。 */
    @Test
    void rejectsInvalidMarketplaceContracts() throws Exception {
        assertThrows(BusinessException.class, () -> clients.parseN8n(objectMapper.readTree("{}")));
        assertThrows(BusinessException.class, () -> clients.parseDify(objectMapper.readTree("{\"data\":{}}")));
    }
}
