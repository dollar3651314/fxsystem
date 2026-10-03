package com.falconx.trading.entity;

/**
 * 持仓保证金模式。
 *
 * <p>Stage 7 第一刀先把 schema 与 owner 落库能力补齐，
 * 当前真实运行态仅支持 `ISOLATED`，`CROSS` 继续保留为后续扩展枚举值。
 */
public enum TradingMarginMode {
    CROSS,
    ISOLATED
}
