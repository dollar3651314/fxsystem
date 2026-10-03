package com.falconx.trading.repository;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.entity.TradingRiskConfig;
import com.falconx.trading.repository.mapper.TradingRiskConfigMapper;
import com.falconx.trading.repository.mapper.record.TradingRiskConfigRecord;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 风险阈值配置 Repository 的 MyBatis 实现。
 *
 * <p>该实现只读取 FX-026 当前所需的 `hedge_threshold_usd`，
 * 不改动其他风控参数的既有使用口径。
 */
@Repository
public class MybatisTradingRiskConfigRepository implements TradingRiskConfigRepository {

    private final TradingRiskConfigMapper tradingRiskConfigMapper;
    private final IdGenerator idGenerator;

    public MybatisTradingRiskConfigRepository(TradingRiskConfigMapper tradingRiskConfigMapper,
                                              IdGenerator idGenerator) {
        this.tradingRiskConfigMapper = tradingRiskConfigMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public Optional<TradingRiskConfig> findBySymbol(String symbol) {
        return Optional.ofNullable(toDomain(tradingRiskConfigMapper.selectBySymbol(symbol)));
    }

    @Override
    public List<String> findSymbolsByMarketCode(String marketCode) {
        return tradingRiskConfigMapper.selectSymbolsByMarketCode(marketCode);
    }

    @Override
    public List<TradingRiskConfig> findAdminPaginated(String symbol, String marketCode, int offset, int limit) {
        return tradingRiskConfigMapper.selectAdminPaginated(blankToNull(symbol), blankToNull(marketCode), offset, limit)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public long countAdminFiltered(String symbol, String marketCode) {
        return tradingRiskConfigMapper.countAdminFiltered(blankToNull(symbol), blankToNull(marketCode));
    }

    @Override
    public void insertAdmin(TradingRiskConfig riskConfig) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TradingRiskConfigRecord record = new TradingRiskConfigRecord(
                idGenerator.nextId(),
                riskConfig.symbol(),
                riskConfig.marketCode(),
                riskConfig.maxPositionPerUser(),
                riskConfig.maxPositionTotal(),
                riskConfig.maintenanceMarginRate(),
                riskConfig.maxLeverage(),
                riskConfig.hedgeThresholdUsd(),
                riskConfig.directionImbalanceRatioThreshold(),
                riskConfig.directionImbalanceMinTotalUsd(),
                riskConfig.pendingOrderMinDistanceRatio(),
                now,
                now
        );
        tradingRiskConfigMapper.insertAdmin(record);
    }

    @Override
    public int updateAdminBySymbol(String symbol, BigDecimal maxPositionPerUser, BigDecimal maxPositionTotal,
                                   Integer maxLeverage, BigDecimal hedgeThresholdUsd) {
        return tradingRiskConfigMapper.updateAdminBySymbol(
                symbol, maxPositionPerUser, maxPositionTotal, maxLeverage, hedgeThresholdUsd,
                LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    public int deleteBySymbol(String symbol) {
        return tradingRiskConfigMapper.deleteBySymbol(symbol);
    }

    @Override
    public Optional<TradingRiskConfig> findPlatformRow() {
        return Optional.ofNullable(toDomain(tradingRiskConfigMapper.selectPlatformRow()));
    }

    @Override
    public Optional<com.falconx.trading.service.model.MarginThresholds> findPlatformMarginThresholds() {
        return Optional.ofNullable(tradingRiskConfigMapper.selectPlatformMarginThresholds());
    }

    @Override
    public Optional<Integer> findPlatformCoolingPeriodSeconds() {
        return Optional.ofNullable(tradingRiskConfigMapper.selectPlatformCoolingPeriodSeconds());
    }

    @Override
    public int updateDirectionImbalance(String symbol, BigDecimal ratioThreshold, BigDecimal minTotalUsd) {
        return tradingRiskConfigMapper.updateDirectionImbalance(
                symbol, ratioThreshold, minTotalUsd, LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    public int updatePlatformHedgeThreshold(BigDecimal hedgeThresholdUsd) {
        return tradingRiskConfigMapper.updatePlatformHedgeThreshold(
                hedgeThresholdUsd, LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    public void updatePlatformCoolingPeriodSeconds(int coolingPeriodSeconds) {
        tradingRiskConfigMapper.updatePlatformCoolingPeriodSeconds(coolingPeriodSeconds);
    }

    @Override
    public void updatePlatformMarginThresholds(BigDecimal stopOutLevel, BigDecimal marginCallLevel) {
        tradingRiskConfigMapper.updatePlatformMarginThresholds(stopOutLevel, marginCallLevel);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private TradingRiskConfig toDomain(TradingRiskConfigRecord record) {
        if (record == null) {
            return null;
        }
        return new TradingRiskConfig(
                record.id(),
                record.symbol(),
                record.marketCode(),
                record.maxPositionPerUser(),
                record.maxPositionTotal(),
                record.maintenanceMarginRate(),
                record.maxLeverage(),
                record.hedgeThresholdUsd(),
                record.directionImbalanceRatioThreshold(),
                record.directionImbalanceMinTotalUsd(),
                record.pendingOrderMinDistanceRatio(),
                TradingMybatisSupport.toOffsetDateTime(record.createdAt()),
                TradingMybatisSupport.toOffsetDateTime(record.updatedAt())
        );
    }
}
