package com.baseai.platform.trace;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TraceType {
    String value();
    String triggerEntry() default "API";
    String ownerIdParameter() default "";
    boolean captureRequest() default true;
}
