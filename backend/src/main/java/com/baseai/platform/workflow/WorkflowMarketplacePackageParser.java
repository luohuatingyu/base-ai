package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 只读取 Dify 插件声明文件，严格禁止加载源码或执行包内内容。 */
@Component
public class WorkflowMarketplacePackageParser {
    private final int maxPackageBytes;
    private final int maxUnpackedBytes;
    private final int maxFiles;

    /** 从工作流市场配置读取压缩包安全上限。 */
    public WorkflowMarketplacePackageParser(PlatformProperties properties) {
        PlatformProperties.Workflow workflow = properties.getWorkflow();
        maxPackageBytes = positive(workflow.getMarketplaceMaxPackageBytes());
        maxUnpackedBytes = positive(workflow.getMarketplaceMaxUnpackedBytes());
        maxFiles = positive(workflow.getMarketplaceMaxPackageFiles());
    }

    /** 校验 provider 与工具 YAML 的引用和稳定工具名称。 */
    public ToolDeclaration requireTool(byte[] archive, String providerPath, String toolPath, String expectedName) {
        if (archive == null || archive.length == 0 || archive.length > maxPackageBytes) invalid();
        Map<String, byte[]> declarations = readDeclarations(archive, List.of("manifest.yaml", providerPath, toolPath));
        Map<String, Object> manifest = yaml(declarations.get("manifest.yaml"));
        Map<String, Object> provider = yaml(declarations.get(providerPath));
        Map<String, Object> tool = yaml(declarations.get(toolPath));
        if (!containsPath(manifest, "plugins", "tools", providerPath)
            || !containsList(provider.get("tools"), toolPath)) invalid();
        Map<String, Object> identity = object(tool.get("identity"));
        String name = string(identity.get("name"));
        if (!expectedName.equals(name)) invalid();
        requireTavilyCredential(provider);
        requirePrimaryParameter(tool, "tavily_search".equals(expectedName) ? "query" : "urls");
        Map<String, Object> labels = object(identity.get("label"));
        Map<String, Object> description = object(tool.get("description"));
        return new ToolDeclaration(name, localized(labels, name), localized(object(description.get("human")), ""));
    }

    /** 流式读取少量声明文件，同时统计全部条目以阻止路径穿越和压缩炸弹。 */
    private Map<String, byte[]> readDeclarations(byte[] archive, List<String> required) {
        Map<String, byte[]> declarations = new LinkedHashMap<>();
        long total = 0;
        int files = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../") || name.equals("..")) invalid();
                if (entry.isDirectory()) continue;
                if (++files > maxFiles) invalid();
                ByteArrayOutputStream selected = required.contains(name) ? new ByteArrayOutputStream() : null;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > maxUnpackedBytes) invalid();
                    if (selected != null) selected.write(buffer, 0, read);
                }
                if (selected != null) {
                    if (declarations.putIfAbsent(name, selected.toByteArray()) != null) invalid();
                }
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("workflow.marketplacePackageInvalid");
        }
        if (!declarations.keySet().containsAll(required)) invalid();
        return declarations;
    }

    /** 使用 SafeConstructor 和资源限制解析不可信 YAML。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> yaml(byte[] content) {
        if (content == null || content.length > 512 * 1024) invalid();
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(20);
            options.setNestingDepthLimit(30);
            options.setCodePointLimit(512 * 1024);
            Object parsed = new Yaml(new SafeConstructor(options)).load(new String(content, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?>)) invalid();
            return (Map<String, Object>) parsed;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("workflow.marketplacePackageInvalid");
        }
    }

    /** 判断嵌套映射中的列表是否包含指定路径。 */
    private boolean containsPath(Map<String, Object> root, String parent, String child, String value) {
        return containsList(object(root.get(parent)).get(child), value);
    }

    /** 判断声明列表是否包含指定字符串。 */
    private boolean containsList(Object value, String expected) {
        return value instanceof List<?> list && list.stream().map(this::string).anyMatch(expected::equals);
    }

    /** 要求官方 Tavily Provider 继续声明必填 secret-input 凭据。 */
    private void requireTavilyCredential(Map<String, Object> provider) {
        Map<String, Object> credential = object(object(provider.get("credentials_for_provider")).get("tavily_api_key"));
        if (!"secret-input".equals(string(credential.get("type")))
            || !Boolean.parseBoolean(string(credential.get("required")))) invalid();
    }

    /** 要求工具关键输入仍为必填字符串，防止市场升级后静默套用旧适配器。 */
    private void requirePrimaryParameter(Map<String, Object> tool, String expectedName) {
        Object parameters = tool.get("parameters");
        if (!(parameters instanceof List<?>)) invalid();
        List<?> list = (List<?>) parameters;
        for (Object value : list) {
            Map<String, Object> parameter = object(value);
            if (expectedName.equals(string(parameter.get("name")))
                && "string".equals(string(parameter.get("type")))
                && Boolean.parseBoolean(string(parameter.get("required")))) return;
        }
        invalid();
    }

    /** 将未知 YAML 值安全收窄为映射。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** 优先读取中文或英文文案。 */
    private String localized(Map<String, Object> values, String fallback) {
        String zh = string(values.get("zh_Hans"));
        String en = string(values.get("en_US"));
        return !zh.isBlank() ? zh : !en.isBlank() ? en : fallback;
    }

    /** 将 YAML 标量安全转为字符串。 */
    private String string(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    /** 返回至少为一的安全配置值。 */
    private int positive(int value) { return Math.max(1, value); }

    /** 统一抛出不泄露包内细节的错误。 */
    private void invalid() { throw new BusinessException("workflow.marketplacePackageInvalid"); }

    public record ToolDeclaration(String name, String label, String description) {}
}
