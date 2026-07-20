package com.baseai.platform.job;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JobType {
    String value();
    String triggerEntry() default "API";
    String ownerIdParameter() default "";
    boolean captureRequest() default true;
}
