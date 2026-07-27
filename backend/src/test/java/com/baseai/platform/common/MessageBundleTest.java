package com.baseai.platform.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageBundleTest {
    private static final Pattern MESSAGE_KEY = Pattern.compile(
        "\\\"((?:error|auth|apiKey|internal|trace|user|role|menu|department|position|setting|dictionary|ai|apiTrigger|llm)\\.[A-Za-z][A-Za-z0-9]*)\\\"");

    /** 中英文资源必须包含完全相同的消息键，避免特定语言请求运行时失败。 */
    @Test
    void englishAndChineseBundlesUseSameKeys() {
        ResourceBundle english = ResourceBundle.getBundle("messages", Locale.US);
        ResourceBundle chinese = ResourceBundle.getBundle("messages", Locale.SIMPLIFIED_CHINESE);

        assertEquals(english.keySet(), chinese.keySet());
    }

    /** 英文资源不得残留中文字符，确保英文语言状态得到纯英文响应。 */
    @Test
    void englishBundleContainsNoChineseMessages() {
        ResourceBundle english = ResourceBundle.getBundle("messages", Locale.US);

        english.keySet().forEach(key -> assertFalse(english.getString(key).matches(".*\\p{IsHan}.*"), key));
    }

    /** 业务代码引用的消息键必须同时存在于中英文资源中。 */
    @Test
    void sourceMessageKeysExistInBothBundles() throws IOException {
        ResourceBundle english = ResourceBundle.getBundle("messages", Locale.US);
        ResourceBundle chinese = ResourceBundle.getBundle("messages", Locale.SIMPLIFIED_CHINESE);
        Set<String> referencedKeys = new TreeSet<>();
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (Path path : paths.filter(item -> item.toString().endsWith(".java")).toList()) {
                Matcher matcher = MESSAGE_KEY.matcher(Files.readString(path));
                while (matcher.find()) referencedKeys.add(matcher.group(1));
            }
        }

        referencedKeys.forEach(key -> {
            assertTrue(english.containsKey(key), "Missing English message key: " + key);
            assertTrue(chinese.containsKey(key), "Missing Chinese message key: " + key);
        });
    }
}
