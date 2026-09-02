package com.baseai.platform.security;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class DataScopeAspect {
    private final DataScopeResolver resolver;

    public DataScopeAspect(DataScopeResolver resolver) {
        this.resolver = resolver;
    }

    /** 在声明数据权限的方法执行期间提供统一过滤范围。 */
    @Around("@annotation(com.baseai.platform.security.DataScope)")
    public Object apply(ProceedingJoinPoint point) throws Throwable {
        DataScopeContext.set(resolver.resolveCurrent());
        try {
            return point.proceed();
        } finally {
            DataScopeContext.clear();
        }
    }

}
