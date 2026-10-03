package com.falconx.trading.repository;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.entity.TradingLiquidationLog;
import com.falconx.trading.repository.mapper.TradingLiquidationLogMapper;
import com.falconx.trading.repository.mapper.record.TradingLiquidationLogRecord;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 强平日志 Repository 的 MyBatis 实现。
 */
@Repository
public class MybatisTradingLiquidationLogRepository implements TradingLiquidationLogRepository {

    private final TradingLiquidationLogMapper tradingLiquidationLogMapper;
    private final IdGenerator idGenerator;

    public MybatisTradingLiquidationLogRepository(TradingLiquidationLogMapper tradingLiquidationLogMapper,
                                                  IdGenerator idGenerator) {
        this.tradingLiquidationLogMapper = tradingLiquidationLogMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public TradingLiquidationLog save(TradingLiquidationLog liquidationLog) {
        long id = liquidationLog.liquidationLogId() == null ? idGenerator.nextId() : liquidationLog.liquidationLogId();
        TradingLiquidationLog persisted = new TradingLiquidationLog(
                id,
                liquidationLog.userId(),
                liquidationLog.positionId(),
                liquidationLog.symbol(),
                liquidationLog.side(),
                liquidationLog.marginMode(),
                liquidationLog.quantity(),
                liquidationLog.entryPrice(),
                liquidationLog.liquidationPrice(),
                liquidationLog.markPrice(),
                liquidationLog.priceTs(),
                liquidationLog.priceSource(),
                liquidationLog.loss(),
                liquidationLog.fee(),
                liquidationLog.marginReleased(),
                liquidationLog.platformCoveredLoss(),
                liquidationLog.createdAt() == null ? OffsetDateTime.now() : liquidationLog.createdAt()
        );
        tradingLiquidationLogMapper.insertTradingLiquidationLog(toRecord(persisted));
        return persisted;
    }

    @Override
    public List<TradingLiquidationLog> findByUserIdPaginated(Long userId, int offset, int limit) {
        return tradingLiquidationLogMapper.selectByUserIdPaginated(userId, offset, limit)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countByUserId(Long userId) {
        return tradingLiquidationLogMapper.countByUserId(userId);
    }

    private TradingLiquidationLogRecord toRecord(TradingLiquidationLog liquidationLog) {
        return new TradingLiquidationLogRecord(
                liquidationLog.liquidationLogId(),
                liquidationLog.userId(),
                liquidationLog.positionId(),
                liquidationLog.symbol(),
                TradingMybatisSupport.toSideCode(liquidationLog.side()),
                TradingMybatisSupport.toMarginModeCode(liquidationLog.marginMode()),
                liquidationLog.quantity(),
                liquidationLog.entryPrice(),
                liquidationLog.liquidationPrice(),
                liquidationLog.markPrice(),
                TradingMybatisSupport.toLocalDateTime(liquidationLog.priceTs()),
                liquidationLog.priceSource(),
                liquidationLog.loss(),
                liquidationLog.fee(),
                liquidationLog.marginReleased(),
                liquidationLog.platformCoveredLoss(),
                TradingMybatisSupport.toLocalDateTime(liquidationLog.createdAt())
        );
    }

    private TradingLiquidationLog toDomain(TradingLiquidationLogRecord record) {
        if (record == null) {
            return null;
        }
        return new TradingLiquidationLog(
                record.id(),
                record.userId(),
                record.positionId(),
                record.symbol(),
                TradingMybatisSupport.toSide(record.sideCode()),
                TradingMybatisSupport.toMarginMode(record.marginModeCode()),
                record.quantity(),
                record.entryPrice(),
                record.liquidationPrice(),
                record.markPrice(),
                TradingMybatisSupport.toOffsetDateTime(record.priceTs()),
                record.priceSource(),
                record.loss(),
                record.fee(),
                record.marginReleased(),
                record.platformCoveredLoss(),
                TradingMybatisSupport.toOffsetDateTime(record.createdAt())
        );
    }
}
