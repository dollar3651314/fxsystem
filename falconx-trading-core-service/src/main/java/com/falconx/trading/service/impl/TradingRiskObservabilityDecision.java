package com.falconx.trading.service.impl;

import com.falconx.trading.entity.TradingHedgeLogStatus;
import java.math.BigDecimal;

/**
 * 风险可观测性判断结果。
 *
 * @param actionStatus      需要写入的告警状态；{@code null} 表示本次无需新增观测记录
 * @param netExposureUsd    按当前 mark price 计算出的净美元敞口
 * @param publishAlertEvent 是否需要触发 Spring Event 告警桩
 * @param breached          当前敞口是否超阈值（无论是否需要写日志）；用于驱动风控动作激活/停用
 */
record TradingRiskObservabilityDecision(
        TradingHedgeLogStatus actionStatus,
        BigDecimal netExposureUsd,
        boolean publishAlertEvent,
        boolean breached
) {

    boolean shouldWriteHedgeLog() {
        return actionStatus != null;
    }
}
