package com.falconx.console.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级 RBAC 鉴权注解。
 *
 * <p>用法：在 controller 方法（或类）上声明 {@code @RequiresPermission("customer:freeze")}，
 * 由 {@link PermissionGuardAspect} 在执行前拦截：
 *
 * <ul>
 *   <li>SUPER_ADMIN 直接放行（不查 t_admin_role_permission）</li>
 *   <li>普通管理员：检查当前 {@link AdminPrincipal} 的权限码集合是否包含 {@link #value()}</li>
 *   <li>未通过 → 抛 {@link com.falconx.console.error.AdminBusinessException}
 *       带 {@link com.falconx.console.error.AdminErrorCode#ADMIN_PERMISSION_DENIED}（90004）</li>
 * </ul>
 *
 * <p>本注解同时被 {@link AdminPermissionDictionaryInitializer} 在启动时扫描，
 * 写入 {@code t_admin_permission} 字典（{@code module = code 中 ":" 之前部分；action = ":" 之后部分}）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /**
     * 权限码，格式 {@code {module}:{action}}（如 {@code customer:freeze}）。
     *
     * @return 权限码
     */
    String value();

    /**
     * 权限点描述，写入 {@code t_admin_permission.description}。
     *
     * @return 描述
     */
    String description() default "";
}
