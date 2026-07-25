package com.baseai.platform.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 显式声明允许由 API Key 授权开放的控制器接口。 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiKeyEndpoint {
    String code();
    String name();
    String group();
    ApiKeyRisk risk() default ApiKeyRisk.NORMAL;
}
