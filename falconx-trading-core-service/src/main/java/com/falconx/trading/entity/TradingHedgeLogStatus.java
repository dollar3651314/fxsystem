package com.falconx.trading.entity;

/**
 * 对冲观测日志状态。
 *
 * <p>当前 Stage 7 只落地“超阈值告警”和“恢复到阈值内”两种基础状态，
 * 真实 A-book 请求与成交状态留到后续对冲接入阶段扩展。
 */
public enum TradingHedgeLogStatus {
    ALERT_ONLY,
    RECOVERED
}
