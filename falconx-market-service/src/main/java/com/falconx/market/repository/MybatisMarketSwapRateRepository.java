package com.falconx.market.repository;

import com.falconx.market.entity.MarketSwapRate;
import com.falconx.market.repository.mapper.MarketSwapRateMapper;
import com.falconx.market.repository.mapper.record.MarketSwapRateRecord;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 隔夜利息费率仓储的 MyBatis 实现。
 *
 * <p>该实现负责把 `t_swap_rate` 的正式 owner 记录转换为领域对象，
 * 不在运行时代码中额外维护任何静态费率白名单。
 */
@Repository
public class MybatisMarketSwapRateRepository implements MarketSwapRateRepository {

    private final MarketSwapRateMapper marketSwapRateMapper;

    public MybatisMarketSwapRateRepository(MarketSwapRateMapper marketSwapRateMapper) {
        this.marketSwapRateMapper = marketSwapRateMapper;
    }

    @Override
    public List<MarketSwapRate> findAllOrdered() {
        return marketSwapRateMapper.selectAllOrdered().stream()
                .map(this::toDomain)
                .toList();
    }

    private MarketSwapRate toDomain(MarketSwapRateRecord record) {
        return new MarketSwapRate(
                record.id(),
                record.symbol(),
                record.longRate(),
                record.shortRate(),
                record.rolloverTime(),
                record.effectiveFrom(),
                MarketMetadataMybatisSupport.toOffsetDateTime(record.createdAt()),
                MarketMetadataMybatisSupport.toOffsetDateTime(record.updatedAt())
        );
    }
}
