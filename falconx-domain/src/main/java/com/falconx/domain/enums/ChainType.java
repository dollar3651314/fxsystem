package com.falconx.domain.enums;

/**
 * 一期支持的钱包链类型。
 *
 * <p>该枚举属于跨服务稳定领域原语，identity、wallet、trading-core 在事件和接口契约里都可能复用。
 */
public enum ChainType {
    ETH,
    BSC,
    TRON,
    SOL
}
