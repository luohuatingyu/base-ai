package com.baseai.platform.security;

import java.nio.charset.StandardCharsets;

/** 统一校验登录密码长度、BCrypt 字节上限和字符类别。 */
public final class PasswordPolicy {
    private PasswordPolicy() { }

    /** 判断密码是否同时包含大小写字母、数字和符号。 */
    public static boolean hasRequiredCharacterClasses(String value) {
        if (value == null) return false;
        boolean lower = false;
        boolean upper = false;
        boolean digit = false;
        boolean symbol = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            lower |= Character.isLowerCase(character);
            upper |= Character.isUpperCase(character);
            digit |= Character.isDigit(character);
            symbol |= !Character.isLetterOrDigit(character);
        }
        return lower && upper && digit && symbol;
    }

    /** 返回 UTF-8 编码后的密码字节数，供 BCrypt 上限检查复用。 */
    public static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }
}
