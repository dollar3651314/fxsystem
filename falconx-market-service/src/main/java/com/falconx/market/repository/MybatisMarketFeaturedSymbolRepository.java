package com.falconx.market.repository;

import com.falconx.market.entity.MarketFeaturedSymbol;
import com.falconx.market.repository.mapper.MarketFeaturedSymbolMapper;
import com.falconx.market.repository.mapper.record.MarketFeaturedSymbolRecord;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 跑马灯热门产品仓储 MyBatis 实现。全量替换在单事务内 deleteAll + batchInsert。
 */
@Repository
public class MybatisMarketFeaturedSymbolRepository implements MarketFeaturedSymbolRepository {

    private final MarketFeaturedSymbolMapper mapper;

    public MybatisMarketFeaturedSymbolRepository(MarketFeaturedSymbolMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<MarketFeaturedSymbol> findAllOrdered() {
        return mapper.selectAllOrdered().stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public void replaceAll(List<MarketFeaturedSymbol> items) {
        mapper.deleteAll();
        if (items.isEmpty()) {
            return;
        }
        List<MarketFeaturedSymbolRecord> rows = items.stream()
                .map(it -> new MarketFeaturedSymbolRecord(
                        it.platformSymbol(), it.sortOrder(), it.enabled(), null, null))
                .toList();
        mapper.batchInsert(rows);
    }

    private MarketFeaturedSymbol toDomain(MarketFeaturedSymbolRecord record) {
        return new MarketFeaturedSymbol(
                record.platformSymbol(),
                record.sortOrder() == null ? 0 : record.sortOrder(),
                record.enabled() == null ? 0 : record.enabled(),
                record.createdAt(),
                record.updatedAt()
        );
    }
}
