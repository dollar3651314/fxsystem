package com.falconx.trading.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * STAGE-14B Task 3：trading-core 内存 FX 汇率服务接口。
 *
 * <p>内存数据来源：
 * <ul>
 *   <li>启动时：调 market {@code GET /internal/v1/market/fx/rates} 一次性拉取全量</li>
 *   <li>运行时：@KafkaListener 消费 {@code falconx.market.fx.rate.update} 增量刷新</li>
 * </ul>
 *
 * <p>查询支持直接对、反向对与 USD pivot 交叉换算；精度 8 位 HALF_UP。
 */
public interface FxRateService {

    /**
     * 查询 from → to 的实时汇率（含 USD pivot 交叉换算）。
     *
     * @param from 基础货币，如 "EUR"
     * @param to   计价货币，如 "AUD"
     * @return 1 from = rate × to；内存中找不到对应路径时返回 empty
     */
    Optional<BigDecimal> queryRate(String from, String to);

    /**
     * 接收一条新 FX rate，写入内存缓存（由 Kafka consumer 调用）。
     *
     * @param base             基础货币
     * @param quote            计价货币
     * @param rate             汇率
     * @param eventTimeMillis  行情时间戳（毫秒）
     */
    void acceptUpdate(String base, String quote, BigDecimal rate, long eventTimeMillis);

    /**
     * 快照当前所有已知 rate（供 metrics / debug 用）。
     *
     * @return 不可变快照，key 格式为 "BASE/QUOTE"
     */
    Map<String, BigDecimal> snapshotAll();
}
