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
    String nameKey();
    String groupKey();
    String descriptionKey() default "";
    ApiKeyRisk risk() default ApiKeyRisk.NORMAL;
    ApiKeyField[] pathParameters() default {};
    ApiKeyField[] requestFields() default {};
    ApiKeyField[] responseFields() default {};
    String requestExample() default "";
    String responseExample() default "";
}
