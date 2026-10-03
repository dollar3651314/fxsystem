package com.falconx.console.entity;

/**
 * STAGE-13 系统配置分类。
 *
 * <p>用于 admin UI 分组展示 + 不同分类权限粒度（未来可扩展）。
 */
public enum SystemConfigCategory {
    /** 限流速率参数（每分钟/每秒请求数） */
    RATE_LIMIT,
    /** 网络安全（可信代理 IP、IP 白名单、Security Headers） */
    SECURITY,
    /** Token TTL / JWT 配置 */
    TOKEN,
    /** 认证策略（bcrypt 强度、登录锁定） */
    AUTH,
    /** HTTP Header 注入（CSP / HSTS 等） */
    HEADER
}
