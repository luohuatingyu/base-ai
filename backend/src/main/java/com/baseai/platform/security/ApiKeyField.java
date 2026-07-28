package com.baseai.platform.security;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 描述 API Key 开放接口的单个请求或响应字段。 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiKeyField {
    String name();
    String descriptionKey();
    String type();
    boolean required() default false;
    String defaultValue() default "";
    String example() default "";
}
