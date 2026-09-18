package com.baseai.platform.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 限流注解，用于标记需要限流的接口或方法。
 * 支持基于IP、用户或全局的限流策略。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    
    /**
     * 限流器名称，用于区分不同的限流策略。
     * 如果不指定，默认使用方法名。
     */
    String name() default "";
    
    /**
     * 限流周期内允许的最大请求数。
     */
    int limit() default 10;
    
    /**
     * 限流周期时长。
     */
    long period() default 60;
    
    /**
     * 限流周期时间单位。
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;
    
    /**
     * 限流类型：IP、用户或全局。
     */
    LimitType limitType() default LimitType.USER;
    
    /**
     * 限流类型枚举。
     */
    enum LimitType {
        /**
         * 基于IP地址限流。
         */
        IP,
        
        /**
         * 基于用户ID限流。
         */
        USER,
        
        /**
         * 全局限流。
         */
        GLOBAL
    }
}
