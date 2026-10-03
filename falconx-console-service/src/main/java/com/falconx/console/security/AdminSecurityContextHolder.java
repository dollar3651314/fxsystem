package com.falconx.console.security;

/**
 * 当前请求线程的管理员上下文持有者。
 *
 * <p>由 {@link AdminAuthenticationFilter} 在请求开始时通过 {@link #set(AdminPrincipal)} 写入；
 * filter 链结束后通过 {@link #clear()} 清理（finally 块保障）。
 *
 * <p>controller / service 层通过 {@link #current()} 获取当前管理员；未登录时返回 {@code null}。
 *
 * <p>线程安全约束：使用 {@link ThreadLocal} 实现，禁止在异步线程跨线程透传；
 * 若必须切换线程（如 @Async / 线程池任务），调用方需显式传递 {@link AdminPrincipal}。
 */
public final class AdminSecurityContextHolder {

    private static final ThreadLocal<AdminPrincipal> CURRENT = new ThreadLocal<>();

    private AdminSecurityContextHolder() {
    }

    /**
     * 写入当前线程的管理员上下文。
     *
     * @param principal 管理员上下文，{@code null} 表示清除
     */
    public static void set(AdminPrincipal principal) {
        if (principal == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(principal);
        }
    }

    /**
     * 获取当前线程的管理员上下文。
     *
     * @return 已登录返回 {@link AdminPrincipal}；未登录返回 {@code null}
     */
    public static AdminPrincipal current() {
        return CURRENT.get();
    }

    /**
     * 清除当前线程的上下文（filter 链 finally 中调用，避免线程池复用导致脏读）。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
