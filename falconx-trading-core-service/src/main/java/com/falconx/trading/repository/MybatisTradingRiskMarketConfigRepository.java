package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingRiskMarketConfig;
import com.falconx.trading.repository.mapper.TradingRiskMarketConfigMapper;
import com.falconx.trading.repository.mapper.record.TradingRiskMarketConfigRecord;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 跨品种集中度阈值配置 Repository 的 MyBatis 实现。
 */
@Repository
public class MybatisTradingRiskMarketConfigRepository implements TradingRiskMarketConfigRepository {

    private final TradingRiskMarketConfigMapper mapper;

    public MybatisTradingRiskMarketConfigRepository(TradingRiskMarketConfigMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<TradingRiskMarketConfig> findByMarketCode(String marketCode) {
        return Optional.ofNullable(toDomain(mapper.selectByMarketCode(marketCode)));
    }

    @Override
    public List<TradingRiskMarketConfig> findAll() {
        return mapper.selectAll().stream().map(this::toDomain).toList();
    }

    @Override
    public int updateAdminByMarketCode(String marketCode, BigDecimal concentrationThresholdUsd, boolean enabled) {
        return mapper.updateAdminByMarketCode(marketCode, concentrationThresholdUsd,
                enabled ? 1 : 0, LocalDateTime.now(ZoneOffset.UTC));
    }

    private TradingRiskMarketConfig toDomain(TradingRiskMarketConfigRecord record) {
        if (record == null) {
            return null;
        }
        return new TradingRiskMarketConfig(
                record.marketCode(),
                record.concentrationThresholdUsd(),
                record.isEnabled() != null && record.isEnabled() == 1,
                TradingMybatisSupport.toOffsetDateTime(record.createdAt()),
                TradingMybatisSupport.toOffsetDateTime(record.updatedAt())
        );
    }
}
