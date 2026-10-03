package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingRiskExposure;
import com.falconx.trading.repository.mapper.TradingRiskExposureMapper;
import com.falconx.trading.repository.mapper.record.TradingRiskExposureRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 品种净敞口 Repository 的 MyBatis 实现。
 *
 * <p>该实现把开仓方向映射为多头或空头增量，
 * 并在同一本地事务里完成 `t_risk_exposure` 更新。
 */
@Repository
public class MybatisTradingRiskExposureRepository implements TradingRiskExposureRepository {

    private final TradingRiskExposureMapper tradingRiskExposureMapper;

    public MybatisTradingRiskExposureRepository(TradingRiskExposureMapper tradingRiskExposureMapper) {
        this.tradingRiskExposureMapper = tradingRiskExposureMapper;
    }

    @Override
    public void applyOpenPosition(String symbol,
                                  TradingOrderSide side,
                                  BigDecimal quantity,
                                  BigDecimal bidPrice,
                                  BigDecimal askPrice,
                                  BigDecimal fxRate,
                                  OffsetDateTime occurredAt) {
        if (side == TradingOrderSide.BUY) {
            tradingRiskExposureMapper.applyLongDelta(
                    symbol,
                    quantity,
                    bidPrice,
                    TradingMybatisSupport.toLocalDateTime(occurredAt)
            );
        } else {
            tradingRiskExposureMapper.applyShortDelta(
                    symbol,
                    quantity,
                    askPrice,
                    TradingMybatisSupport.toLocalDateTime(occurredAt)
            );
        }
        refreshNetExposureUsd(
                symbol,
                bidPrice,
                askPrice,
                fxRate,
                occurredAt
        );
    }

    @Override
    public void applyClosePosition(String symbol,
                                   TradingOrderSide side,
                                   BigDecimal quantity,
                                   BigDecimal bidPrice,
                                   BigDecimal askPrice,
                                   BigDecimal fxRate,
                                   OffsetDateTime occurredAt) {
        int affectedRows = side == TradingOrderSide.BUY
                ? tradingRiskExposureMapper.reduceLongDelta(
                symbol,
                quantity,
                bidPrice,
                TradingMybatisSupport.toLocalDateTime(occurredAt)
        )
                : tradingRiskExposureMapper.reduceShortDelta(
                symbol,
                quantity,
                askPrice,
                TradingMybatisSupport.toLocalDateTime(occurredAt)
        );
        if (affectedRows != 1) {
            throw new IllegalStateException("Risk exposure not found or inconsistent for symbol=" + symbol);
        }
        refreshNetExposureUsd(symbol, bidPrice, askPrice, fxRate, occurredAt);
    }

    @Override
    public void refreshNetExposureUsd(String symbol,
                                      BigDecimal bidPrice,
                                      BigDecimal askPrice,
                                      BigDecimal fxRate,
                                      OffsetDateTime occurredAt) {
        // 多币种 USD 化：fx null 降级 1（与历史口径一致），SQL 端直接相乘不做 NULL 容忍
        tradingRiskExposureMapper.refreshNetExposureUsd(
                symbol,
                bidPrice,
                askPrice,
                fxRate == null ? java.math.BigDecimal.ONE : fxRate,
                TradingMybatisSupport.toLocalDateTime(occurredAt)
        );
    }

    @Override
    public Optional<TradingRiskExposure> findBySymbol(String symbol) {
        return Optional.ofNullable(toDomain(tradingRiskExposureMapper.selectBySymbol(symbol)));
    }

    @Override
    public List<TradingRiskExposure> findBySymbols(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return List.of();
        }
        return tradingRiskExposureMapper.selectBySymbols(symbols).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public BigDecimal sumAbsNetExposureUsdByMarketCode(String marketCode) {
        BigDecimal result = tradingRiskExposureMapper.sumAbsNetExposureUsdByMarketCode(marketCode);
        return result == null ? BigDecimal.ZERO : result;
    }

    @Override
    public List<TradingRiskExposure> selectAdminAll(String symbol) {
        return tradingRiskExposureMapper.selectAdminAll(symbol).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public BigDecimal sumAbsNetExposureUsdAllSymbols() {
        BigDecimal result = tradingRiskExposureMapper.sumAbsNetExposureUsdAllSymbols();
        return result == null ? BigDecimal.ZERO : result;
    }

    private TradingRiskExposure toDomain(TradingRiskExposureRecord record) {
        if (record == null) {
            return null;
        }
        return new TradingRiskExposure(
                record.symbol(),
                record.totalLongQty(),
                record.totalShortQty(),
                record.netExposure(),
                record.netExposureUsd(),
                TradingMybatisSupport.toOffsetDateTime(record.updatedAt())
        );
    }
}
