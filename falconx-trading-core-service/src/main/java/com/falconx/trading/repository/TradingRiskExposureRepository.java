package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingRiskExposure;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 品种净敞口仓储接口。
 *
 * <p>该仓储负责把平台总净敞口持久化到 `t_risk_exposure`，
 * 并要求和开仓、平仓、强平处于同一本地事务内更新。
 */
public interface TradingRiskExposureRepository {

    /**
     * 记录一次持仓增加后的敞口变化。
     *
     * @param symbol 交易品种
     * @param side 开仓方向
     * @param quantity 增量数量
     * @param markPrice 本次估值使用的标记价
     * @param occurredAt 发生时间
     */
    void applyOpenPosition(String symbol,
                           TradingOrderSide side,
                           BigDecimal quantity,
                           BigDecimal bidPrice,
                           BigDecimal askPrice,
                           BigDecimal fxRate,
                           OffsetDateTime occurredAt);

    /**
     * 记录一次持仓减少后的敞口变化。
     *
     * @param symbol 交易品种
     * @param side 原始持仓方向
     * @param quantity 回补数量
     * @param markPrice 本次估值使用的标记价
     * @param occurredAt 发生时间
     */
    void applyClosePosition(String symbol,
                            TradingOrderSide side,
                            BigDecimal quantity,
                            BigDecimal bidPrice,
                            BigDecimal askPrice,
                            BigDecimal fxRate,
                            OffsetDateTime occurredAt);

    /**
     * 仅按最新标记价刷新美元口径净敞口（多币种 USD 化：×fx(QC→USD)，null 降级 1）。
     *
     * <p>该方法不改变数量敞口，只把 `net_exposure_usd` 更新到最新行情口径，
     * 供 `QuoteDrivenEngine` 在价格变化但仓位未变化时持续观测平台风险规模。
     *
     * @param symbol 交易品种
     * @param markPrice 最新标记价
     * @param occurredAt 发生时间
     */
    void refreshNetExposureUsd(String symbol, BigDecimal bidPrice, BigDecimal askPrice, BigDecimal fxRate, OffsetDateTime occurredAt);

    /**
     * 查询某个品种当前敞口。
     *
     * @param symbol 交易品种
     * @return 敞口快照
     */
    Optional<TradingRiskExposure> findBySymbol(String symbol);

    /**
     * 批量查询多个品种当前敞口。
     *
     * <p>用于风险观测相关性组计算，避免 tick 热路径按组成员逐个查库。
     *
     * @param symbols 交易品种列表
     * @return 已存在的敞口快照列表
     */
    List<TradingRiskExposure> findBySymbols(List<String> symbols);

    /**
     * 按品种分类汇总净美元敞口绝对值之和（跨品种集中度检查使用）。
     *
     * @param marketCode 品种分类
     * @return 净美元敞口绝对值合计
     */
    BigDecimal sumAbsNetExposureUsdByMarketCode(String marketCode);

    /**
     * STAGE-2-TRADING-MONITOR R4：管理端按 |net_exposure_usd| DESC 查询全部敞口。
     *
     * @param symbol 可选筛选；null/空串时不限
     * @return 敞口列表
     */
    List<TradingRiskExposure> selectAdminAll(String symbol);

    /**
     * BBOOK-RISK-CONTROL-01：平台总净美元敞口绝对值合计（跨全部 symbol）。
     */
    BigDecimal sumAbsNetExposureUsdAllSymbols();
}
