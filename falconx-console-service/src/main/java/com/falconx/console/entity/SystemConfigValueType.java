package com.falconx.console.entity;

/**
 * STAGE-13 系统配置值类型。
 *
 * <p>service 读取时按此类型解析 {@code config_value} 字符串。
 */
public enum SystemConfigValueType {
    /** 普通字符串（如 IP 地址、URL） */
    STRING,
    /** 32 位整数 */
    INT,
    /** 64 位整数 */
    LONG,
    /** 高精度小数（如 BigDecimal） */
    DECIMAL,
    /** 布尔（true/false） */
    BOOL,
    /** Duration 字符串（如 "1h", "30m"，Java 标准 ISO-8601 + Spring 简短记法） */
    DURATION,
    /** JSON 字符串（数组 / 对象，由 service 自行 deserialize） */
    JSON
}
