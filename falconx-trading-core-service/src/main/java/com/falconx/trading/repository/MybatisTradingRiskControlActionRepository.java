package com.falconx.trading.repository;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.entity.TradingRiskControlActionType;
import com.falconx.trading.repository.mapper.TradingRiskControlActionMapper;
import com.falconx.trading.repository.mapper.record.TradingRiskControlActionRecord;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * BBook 风控执行动作 Repository 的 MyBatis 实现。
 */
@Repository
public class MybatisTradingRiskControlActionRepository implements TradingRiskControlActionRepository {

    private final TradingRiskControlActionMapper mapper;
    private final IdGenerator idGenerator;

    public MybatisTradingRiskControlActionRepository(TradingRiskControlActionMapper mapper,
                                                     IdGenerator idGenerator) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public boolean activateIfAbsent(String symbol,
                                    TradingRiskControlActionType actionType,
                                    String triggerSource,
                                    String triggerReason,
                                    Long hedgeLogId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TradingRiskControlActionRecord record = new TradingRiskControlActionRecord(
                idGenerator.nextId(),
                symbol,
                TradingMybatisSupport.toRiskControlActionTypeCode(actionType),
                1,
                triggerSource,
                triggerReason,
                hedgeLogId,
                now,
                now
        );
        return mapper.insertIfAbsent(record) > 0;
    }

    @Override
    public void deactivate(String symbol, TradingRiskControlActionType actionType, String triggerSource) {
        mapper.deactivateBySymbolAndTypeAndSource(
                symbol,
                TradingMybatisSupport.toRiskControlActionTypeCode(actionType),
                triggerSource,
                LocalDateTime.now(ZoneOffset.UTC)
        );
    }

    @Override
    public void deactivateBatch(List<String> symbols, TradingRiskControlActionType actionType, String triggerSource) {
        if (symbols == null || symbols.isEmpty()) {
            return;
        }
        mapper.deactivateBySymbolsAndTypeAndSource(
                symbols,
                TradingMybatisSupport.toRiskControlActionTypeCode(actionType),
                triggerSource,
                LocalDateTime.now(ZoneOffset.UTC)
        );
    }

    @Override
    public Optional<TradingRiskControlActionType> findMostSevereActiveBySymbol(String symbol) {
        TradingRiskControlActionRecord record = mapper.selectMostSevereActiveBySymbol(symbol);
        if (record == null) {
            return Optional.empty();
        }
        return Optional.of(TradingMybatisSupport.toRiskControlActionType(record.actionTypeCode()));
    }

    @Override
    public boolean hasActiveGlobalPause() {
        return mapper.countActiveGlobalPause() > 0;
    }

    @Override
    public java.util.List<com.falconx.trading.entity.TradingRiskControlAction> findAdminPaginated(
            String symbol, TradingRiskControlActionType actionType, String triggerSource,
            Boolean isActive, java.time.OffsetDateTime fromCreatedAt, java.time.OffsetDateTime toCreatedAt,
            int offset, int limit) {
        Integer actionTypeCode = actionType == null
                ? null
                : TradingMybatisSupport.toRiskControlActionTypeCode(actionType);
        Integer isActiveCode = isActive == null ? null : (isActive ? 1 : 0);
        return mapper.selectAdminPaginated(symbol, actionTypeCode, triggerSource, isActiveCode,
                        toLocalUtc(fromCreatedAt), toLocalUtc(toCreatedAt), offset, limit)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public long countAdminFiltered(String symbol, TradingRiskControlActionType actionType,
                                   String triggerSource, Boolean isActive,
                                   java.time.OffsetDateTime fromCreatedAt, java.time.OffsetDateTime toCreatedAt) {
        Integer actionTypeCode = actionType == null
                ? null
                : TradingMybatisSupport.toRiskControlActionTypeCode(actionType);
        Integer isActiveCode = isActive == null ? null : (isActive ? 1 : 0);
        return mapper.countAdminFiltered(symbol, actionTypeCode, triggerSource, isActiveCode,
                toLocalUtc(fromCreatedAt), toLocalUtc(toCreatedAt));
    }

    private static java.time.LocalDateTime toLocalUtc(java.time.OffsetDateTime ts) {
        return ts == null ? null : ts.atZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
    }

    @Override
    public java.util.Optional<com.falconx.trading.entity.TradingRiskControlAction> findById(long id) {
        return Optional.ofNullable(toDomain(mapper.selectById(id)));
    }

    @Override
    public int deactivateAdminById(long id) {
        return mapper.deactivateAdminById(id, LocalDateTime.now(ZoneOffset.UTC));
    }

    private com.falconx.trading.entity.TradingRiskControlAction toDomain(TradingRiskControlActionRecord record) {
        if (record == null) {
            return null;
        }
        return new com.falconx.trading.entity.TradingRiskControlAction(
                record.id(),
                record.symbol(),
                TradingMybatisSupport.toRiskControlActionType(record.actionTypeCode()),
                record.isActive() != null && record.isActive() == 1,
                record.triggerSource(),
                record.triggerReason(),
                record.hedgeLogId(),
                TradingMybatisSupport.toOffsetDateTime(record.createdAt()),
                TradingMybatisSupport.toOffsetDateTime(record.updatedAt())
        );
    }
}
