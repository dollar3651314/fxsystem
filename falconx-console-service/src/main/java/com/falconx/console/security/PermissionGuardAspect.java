package com.falconx.console.security;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

/**
 * RBAC AOP 切面：拦截 {@link RequiresPermission @RequiresPermission} 方法，调用 {@link PermissionGuardService} 校验。
 *
 * <p>拦截顺序：在 {@link com.falconx.console.security.AdminAuthenticationFilter} 注入 {@link AdminPrincipal} 之后、
 * controller 方法体执行之前。失败时抛 {@link com.falconx.console.error.AdminBusinessException}，
 * 由 {@link com.falconx.console.config.AdminGlobalExceptionHandler} 映射到 90004 响应。
 */
@Aspect
@Component
public class PermissionGuardAspect {

    private final PermissionGuardService permissionGuardService;

    public PermissionGuardAspect(PermissionGuardService permissionGuardService) {
        this.permissionGuardService = permissionGuardService;
    }

    @Before("@annotation(com.falconx.console.security.RequiresPermission)")
    public void enforceMethodLevel(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        RequiresPermission annotation = signature.getMethod().getAnnotation(RequiresPermission.class);
        if (annotation == null) {
            return;
        }
        permissionGuardService.requirePermission(annotation.value());
    }
}
