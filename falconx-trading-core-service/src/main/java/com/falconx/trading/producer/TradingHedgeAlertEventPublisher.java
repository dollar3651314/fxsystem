package com.falconx.trading.producer;

import com.falconx.trading.event.TradingHedgeAlertEvent;

/**
 * 对冲告警桩事件发布器。
 *
 * <p>当前只负责把服务内 Spring Event 延后到事务提交后触发，
 * 避免主事务回滚时外部观察面先看到一条并不存在的超阈值告警。
 */
public interface TradingHedgeAlertEventPublisher {

    /**
     * 在事务提交后发布一条对冲告警桩事件。
     *
     * @param event 告警事件
     */
    void publishAfterCommit(TradingHedgeAlertEvent event);
}
