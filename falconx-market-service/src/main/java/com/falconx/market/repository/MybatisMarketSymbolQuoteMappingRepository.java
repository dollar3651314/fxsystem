package com.falconx.market.repository;

import com.falconx.market.entity.MarketSymbolQuoteMapping;
import com.falconx.market.repository.mapper.MarketSymbolQuoteMappingMapper;
import com.falconx.market.repository.mapper.record.MarketSymbolQuoteMappingRecord;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * symbol 报价源映射仓储的 MyBatis 实现。
 */
@Repository
public class MybatisMarketSymbolQuoteMappingRepository implements MarketSymbolQuoteMappingRepository {

    private final MarketSymbolQuoteMappingMapper mapper;

    public MybatisMarketSymbolQuoteMappingRepository(MarketSymbolQuoteMappingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<MarketSymbolQuoteMapping> findAllLpSubscribedMappings() {
        return mapper.selectAllLpSubscribedMappings().stream()
                .map(this::toDomain)
                .toList();
    }

    private MarketSymbolQuoteMapping toDomain(MarketSymbolQuoteMappingRecord record) {
        return new MarketSymbolQuoteMapping(
                record.platformSymbol(),
                record.sourceProvider(),
                record.sourceLpCode(),
                record.sourceSymbol(),
                record.priceMultiplier(),
                record.bidAdjustment(),
                record.askAdjustment(),
                record.enabled() == null ? 0 : record.enabled(),
                record.lpSubscribeEnabled() == null ? 0 : record.lpSubscribeEnabled()
        );
    }
}
