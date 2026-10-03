package com.falconx.trading.service;

import com.falconx.market.contract.event.MarketGroupMarkupItem;
import java.util.Optional;

/**
 * STAGE-12-GROUP-MARKUP：trading-core 本地组加点查询服务。
 *
 * <p>启动时从 market-service 拉全量配置到内存快照；后续 30s 定时增量刷新。
 * 撮合 / 平仓 / PnL / 强平 / 挂单触发等高频热路径直接调 {@link #find} lookup，
 * 不再走 RPC。
 */
public interface TradingGroupMarkupService {

    /**
     * 启动 / 周期性刷新内存快照。
     */
    void refresh();

    /**
     * 查询 (groupCode, platformSymbol) 对应的加点配置。
     *
     * @return 命中时返回；不存在或禁用时返回空
     */
    Optional<MarketGroupMarkupItem> find(String groupCode, String platformSymbol);
}
