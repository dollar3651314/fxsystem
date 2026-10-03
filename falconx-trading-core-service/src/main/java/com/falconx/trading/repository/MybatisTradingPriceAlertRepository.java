package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingPriceAlert;
import com.falconx.trading.entity.TradingPriceAlertDirection;
import com.falconx.trading.entity.TradingPriceAlertStatus;
import com.falconx.trading.repository.mapper.TradingPriceAlertMapper;
import com.falconx.trading.repository.mapper.record.TradingPriceAlertRecord;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * STAGE-4-PRICE-ALERT：价格告警仓储 MyBatis 实现。
 */
@Repository
public class MybatisTradingPriceAlertRepository implements TradingPriceAlertRepository {

    private final TradingPriceAlertMapper mapper;

    public MybatisTradingPriceAlertRepository(TradingPriceAlertMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(TradingPriceAlert alert) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TradingPriceAlertRecord record = new TradingPriceAlertRecord(
                alert.id(),
                alert.userId(),
                alert.symbol(),
                alert.direction().code(),
                alert.targetPrice(),
                alert.status().code(),
                alert.note(),
                alert.basePrice(),
                alert.triggerCount(),
                alert.lastTriggeredAt() == null ? null : alert.lastTriggeredAt().toLocalDateTime(),
                alert.lastTriggeredPrice(),
                alert.cancelledAt() == null ? null : alert.cancelledAt().toLocalDateTime(),
                alert.cancelSource(),
                now, now
        );
        mapper.insert(record);
    }

    @Override
    public Optional<TradingPriceAlert> findById(Long id) {
        return Optional.ofNullable(toDomain(mapper.selectById(id)));
    }

    @Override
    public Optional<TradingPriceAlert> findByIdForUpdate(Long id) {
        return Optional.ofNullable(toDomain(mapper.selectByIdForUpdate(id)));
    }

    @Override
    public List<TradingPriceAlert> findTriggerableBySymbol(String symbol, OffsetDateTime now) {
        return mapper.selectTriggerableBySymbol(symbol, now.toLocalDateTime())
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<String> findActiveSymbols() {
        return mapper.selectActiveSymbols();
    }

    @Override
    public List<TradingPriceAlert> findByUserId(Long userId, Integer statusCode, String symbol, int offset, int limit) {
        return mapper.selectByUserId(userId, statusCode, symbol, offset, limit)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public long countByUserId(Long userId, Integer statusCode, String symbol) {
        return mapper.countByUserId(userId, statusCode, symbol);
    }

    @Override
    public int countActiveByUserId(Long userId) {
        return mapper.countActiveByUserId(userId);
    }

    @Override
    public long countActiveBySymbol(String symbol) {
        return mapper.countActiveBySymbol(symbol);
    }

    @Override
    public List<TradingPriceAlert> findAdminPaginated(Long userId, String symbol, Integer statusCode, int offset, int limit) {
        return mapper.selectAdminPaginated(userId, symbol, statusCode, offset, limit)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public long countAdminFiltered(Long userId, String symbol, Integer statusCode) {
        return mapper.countAdminFiltered(userId, symbol, statusCode);
    }

    @Override
    public int incrementTrigger(Long id, int expectedTriggerCount, int nextTriggerCount,
                                 int nextStatusCode, BigDecimal lastTriggeredPrice) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return mapper.incrementTriggerAtomic(id, expectedTriggerCount, nextTriggerCount,
                nextStatusCode, now, lastTriggeredPrice, now);
    }

    @Override
    public int markCancelled(Long id, int nextStatusCode, String cancelSource) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return mapper.markCancelledAtomic(id, nextStatusCode, cancelSource, now, now);
    }

    private TradingPriceAlert toDomain(TradingPriceAlertRecord r) {
        if (r == null) return null;
        return new TradingPriceAlert(
                r.id(),
                r.userId(),
                r.symbol(),
                TradingPriceAlertDirection.fromCode(r.direction()),
                r.targetPrice(),
                TradingPriceAlertStatus.fromCode(r.status()),
                r.note(),
                r.basePrice(),
                r.triggerCount() == null ? 0 : r.triggerCount(),
                TradingMybatisSupport.toOffsetDateTime(r.lastTriggeredAt()),
                r.lastTriggeredPrice(),
                TradingMybatisSupport.toOffsetDateTime(r.cancelledAt()),
                r.cancelSource(),
                TradingMybatisSupport.toOffsetDateTime(r.createdAt()),
                TradingMybatisSupport.toOffsetDateTime(r.updatedAt())
        );
    }
}
