package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingUserRiskThreshold;
import com.falconx.trading.repository.mapper.TradingUserRiskThresholdMapper;
import com.falconx.trading.repository.mapper.record.TradingUserRiskThresholdRecord;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * BBOOK-RISK-CONTROL-01：用户级风控阈值仓储 MyBatis 实现。
 */
@Repository
public class MybatisTradingUserRiskThresholdRepository implements TradingUserRiskThresholdRepository {

    private final TradingUserRiskThresholdMapper mapper;

    public MybatisTradingUserRiskThresholdRepository(TradingUserRiskThresholdMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<TradingUserRiskThreshold> findByUserId(Long userId) {
        return Optional.ofNullable(toDomain(mapper.selectByUserId(userId)));
    }

    @Override
    public List<TradingUserRiskThreshold> findAdminPaginated(Long userId, int offset, int limit) {
        return mapper.selectAdminPaginated(userId, offset, limit).stream().map(this::toDomain).toList();
    }

    @Override
    public long countAdminFiltered(Long userId) {
        return mapper.countAdminFiltered(userId);
    }

    @Override
    public int upsert(Long userId, BigDecimal netExposureThresholdUsd,
                      BigDecimal profitableNetExposureThresholdUsd,
                      boolean isProfitableUser, String updatedBy, String updatedReason) {
        return mapper.upsert(userId, netExposureThresholdUsd, profitableNetExposureThresholdUsd,
                isProfitableUser ? 1 : 0, updatedBy, updatedReason,
                LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    public int deleteByUserId(Long userId) {
        return mapper.deleteByUserId(userId);
    }

    private TradingUserRiskThreshold toDomain(TradingUserRiskThresholdRecord record) {
        if (record == null) {
            return null;
        }
        return new TradingUserRiskThreshold(
                record.userId(),
                record.netExposureThresholdUsd(),
                record.profitableNetExposureThresholdUsd(),
                record.isProfitableUser() != null && record.isProfitableUser() == 1,
                record.updatedBy(),
                record.updatedReason(),
                TradingMybatisSupport.toOffsetDateTime(record.createdAt()),
                TradingMybatisSupport.toOffsetDateTime(record.updatedAt())
        );
    }
}
