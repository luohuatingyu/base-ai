package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowMarketplacePackageParserTest {
    /** 合法插件只读取声明文件并确认 provider 到工具的引用。 */
    @Test
    void validatesDeclaredToolWithoutExecutingSource() throws Exception {
        WorkflowMarketplacePackageParser parser = new WorkflowMarketplacePackageParser(new PlatformProperties());
        byte[] archive = zip(Map.of(
            "manifest.yaml", "plugins:\n  tools:\n    - provider/tavily.yaml\n",
            "provider/tavily.yaml", "credentials_for_provider:\n  tavily_api_key:\n    type: secret-input\n    required: true\n"
                + "tools:\n  - tools/tavily_search.yaml\n",
            "tools/tavily_search.yaml", "identity:\n  name: tavily_search\n  label:\n    en_US: Tavily Search\n"
                + "description:\n  human:\n    en_US: Search the web\nparameters:\n"
                + "  - name: query\n    type: string\n    required: true\n",
            "tools/tavily_search.py", "raise RuntimeError('must never run')\n"
        ));

        WorkflowMarketplacePackageParser.ToolDeclaration tool = parser.requireTool(archive,
            "provider/tavily.yaml", "tools/tavily_search.yaml", "tavily_search");

        assertEquals("Tavily Search", tool.label());
        assertEquals("Search the web", tool.description());
    }

    /** 官方关键参数或 secret-input 凭据发生漂移时必须拒绝继续套用旧适配器。 */
    @Test
    void rejectsToolContractDrift() throws Exception {
        WorkflowMarketplacePackageParser parser = new WorkflowMarketplacePackageParser(new PlatformProperties());
        byte[] archive = zip(Map.of(
            "manifest.yaml", "plugins:\n  tools:\n    - provider/tavily.yaml\n",
            "provider/tavily.yaml", "credentials_for_provider:\n  tavily_api_key:\n    type: text-input\n    required: true\n"
                + "tools:\n  - tools/tavily_search.yaml\n",
            "tools/tavily_search.yaml", "identity:\n  name: tavily_search\nparameters:\n"
                + "  - name: query\n    type: number\n    required: true\n"
        ));

        assertThrows(BusinessException.class, () -> parser.requireTool(archive,
            "provider/tavily.yaml", "tools/tavily_search.yaml", "tavily_search"));
    }

    /** 路径穿越条目即使与目标声明无关也必须拒绝。 */
    @Test
    void rejectsZipTraversal() throws Exception {
        WorkflowMarketplacePackageParser parser = new WorkflowMarketplacePackageParser(new PlatformProperties());
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("../escape", "bad");
        entries.put("manifest.yaml", "plugins: {}\n");

        assertThrows(BusinessException.class, () -> parser.requireTool(zip(entries),
            "provider/tavily.yaml", "tools/tavily_search.yaml", "tavily_search"));
    }

    /** 解压后总量超过配置上限时必须在内存中止。 */
    @Test
    void rejectsOversizedUnpackedContent() throws Exception {
        PlatformProperties properties = new PlatformProperties();
        properties.getWorkflow().setMarketplaceMaxUnpackedBytes(20);
        WorkflowMarketplacePackageParser parser = new WorkflowMarketplacePackageParser(properties);

        assertThrows(BusinessException.class, () -> parser.requireTool(zip(Map.of("manifest.yaml", "x".repeat(100))),
            "provider/tavily.yaml", "tools/tavily_search.yaml", "tavily_search"));
    }

    /** 在内存创建测试 ZIP，避免产生调试文件。 */
    private byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
