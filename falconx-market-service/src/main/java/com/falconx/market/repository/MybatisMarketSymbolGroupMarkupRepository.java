package com.falconx.market.repository;

import com.falconx.market.entity.MarketSymbolGroupMarkup;
import com.falconx.market.repository.mapper.MarketSymbolGroupMarkupMapper;
import com.falconx.market.repository.mapper.record.MarketSymbolGroupMarkupRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 用户组加点仓储的 MyBatis 实现。
 */
@Repository
public class MybatisMarketSymbolGroupMarkupRepository implements MarketSymbolGroupMarkupRepository {

    private final MarketSymbolGroupMarkupMapper mapper;

    public MybatisMarketSymbolGroupMarkupRepository(MarketSymbolGroupMarkupMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<MarketSymbolGroupMarkup> findAllEnabled() {
        return mapper.selectAllEnabled().stream().map(this::toDomain).toList();
    }

    @Override
    public List<MarketSymbolGroupMarkup> findChangedSince(OffsetDateTime since) {
        return mapper.selectChangedSince(since).stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<MarketSymbolGroupMarkup> findByPk(String groupCode, String platformSymbol) {
        return Optional.ofNullable(mapper.selectByPk(groupCode, platformSymbol)).map(this::toDomain);
    }

    @Override
    public List<MarketSymbolGroupMarkup> findByFilters(
            String groupCode, String symbolLike, Integer enabled, int offset, int limit) {
        return mapper.selectByFilters(groupCode, symbolLike, enabled, offset, limit).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countByFilters(String groupCode, String symbolLike, Integer enabled) {
        return mapper.countByFilters(groupCode, symbolLike, enabled);
    }

    @Override
    public int upsert(String groupCode, String platformSymbol,
                      BigDecimal bidExtra, BigDecimal askExtra, int enabled) {
        return mapper.upsert(groupCode, platformSymbol, bidExtra, askExtra, enabled);
    }

    @Override
    public int delete(String groupCode, String platformSymbol) {
        return mapper.delete(groupCode, platformSymbol);
    }

    private MarketSymbolGroupMarkup toDomain(MarketSymbolGroupMarkupRecord record) {
        return new MarketSymbolGroupMarkup(
                record.groupCode(),
                record.platformSymbol(),
                record.bidExtra(),
                record.askExtra(),
                record.enabled() == null ? 0 : record.enabled(),
                record.createdAt(),
                record.updatedAt()
        );
    }
}
