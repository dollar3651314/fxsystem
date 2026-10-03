package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingPriceAlert;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * STAGE-4-PRICE-ALERT：价格告警仓储。
 */
public interface TradingPriceAlertRepository {

    void insert(TradingPriceAlert alert);

    Optional<TradingPriceAlert> findById(Long id);

    Optional<TradingPriceAlert> findByIdForUpdate(Long id);

    /**
     * 触发引擎扫描：按 symbol 找所有 ACTIVE + (从未触发 OR 距上次 ≥ 5 分钟) 的告警。
     */
    List<TradingPriceAlert> findTriggerableBySymbol(String symbol, OffsetDateTime now);

    /**
     * 启动预热高频 tick 触发索引用：查询仍有 ACTIVE 价格提醒的 symbol。
     */
    List<String> findActiveSymbols();

    List<TradingPriceAlert> findByUserId(Long userId, Integer statusCode, String symbol,
                                          int offset, int limit);

    long countByUserId(Long userId, Integer statusCode, String symbol);

    /**
     * 用户级 ACTIVE 数量（10 条上限校验）。
     */
    int countActiveByUserId(Long userId);

    long countActiveBySymbol(String symbol);

    List<TradingPriceAlert> findAdminPaginated(Long userId, String symbol, Integer statusCode,
                                                int offset, int limit);

    long countAdminFiltered(Long userId, String symbol, Integer statusCode);

    /**
     * 触发后 CAS 递增。返回影响行数；0 表示并发竞态。
     */
    int incrementTrigger(Long id, int expectedTriggerCount, int nextTriggerCount,
                          int nextStatusCode, BigDecimal lastTriggeredPrice);

    /**
     * 撤销（status ACTIVE → CANCELLED/ADMIN_DELETED）。
     */
    int markCancelled(Long id, int nextStatusCode, String cancelSource);
}
