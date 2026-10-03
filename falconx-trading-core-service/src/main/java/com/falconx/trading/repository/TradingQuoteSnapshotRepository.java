package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingQuoteSnapshot;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 交易内部最新价格仓储接口。
 *
 * <p>该接口用于承接高频 `price.tick` 事件后的最新价格状态，
 * 让同步下单和实时引擎都能读取到统一快照。
 */
public interface TradingQuoteSnapshotRepository {

    /**
     * 保存最新行情快照。
     *
     * @param snapshot 行情快照
     * @return 持久化后的快照
     */
    TradingQuoteSnapshot save(TradingQuoteSnapshot snapshot);

    /**
     * 按品种查询最新行情快照。
     *
     * @param symbol 品种
     * @return 行情快照
     */
    Optional<TradingQuoteSnapshot> findBySymbol(String symbol);

    /**
     * 批量按品种查询最新行情快照（Redis pipeline 1 RTT）。
     *
     * <p>性能背景（Sprint 2 S3）：下单链路风控
     * {@code DefaultTradingRiskService.evaluateUserExposureLimit} 循环对用户每个
     * 持仓 symbol 调单 findBySymbol，N 持仓 = N 次 Redis HGETALL；100 持仓约 10-50ms
     * 都消耗在这。pipeline 后 1 RTT < 5ms。
     *
     * @param symbols 品种代码集合
     * @return symbol → TradingQuoteSnapshot 映射；命中即返回，未命中不出现在 map
     */
    Map<String, TradingQuoteSnapshot> findBySymbols(Collection<String> symbols);
}
