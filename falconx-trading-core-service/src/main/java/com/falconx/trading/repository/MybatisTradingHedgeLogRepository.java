package com.falconx.trading.repository;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.entity.TradingHedgeLog;
import com.falconx.trading.repository.mapper.TradingHedgeLogMapper;
import com.falconx.trading.repository.mapper.record.TradingHedgeLogRecord;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 对冲观测日志 Repository 的 MyBatis 实现。
 *
 * <p>该实现把 FX-026 的阈值告警/恢复事实正式落库到 `t_hedge_log`，
 * 供后续审计和真实对冲接入继续复用。
 */
@Repository
public class MybatisTradingHedgeLogRepository implements TradingHedgeLogRepository {

    private final TradingHedgeLogMapper tradingHedgeLogMapper;
    private final IdGenerator idGenerator;

    public MybatisTradingHedgeLogRepository(TradingHedgeLogMapper tradingHedgeLogMapper,
                                            IdGenerator idGenerator) {
        this.tradingHedgeLogMapper = tradingHedgeLogMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public TradingHedgeLog save(TradingHedgeLog hedgeLog) {
        long id = hedgeLog.hedgeLogId() == null ? idGenerator.nextId() : hedgeLog.hedgeLogId();
        TradingHedgeLog persisted = new TradingHedgeLog(
                id,
                hedgeLog.symbol(),
                hedgeLog.positionId(),
                hedgeLog.triggerSource(),
                hedgeLog.actionStatus(),
                hedgeLog.netExposure(),
                hedgeLog.netExposureUsd(),
                hedgeLog.hedgeThresholdUsd(),
                hedgeLog.markPrice(),
                hedgeLog.priceTs(),
                hedgeLog.priceSource(),
                hedgeLog.createdAt() == null ? OffsetDateTime.now() : hedgeLog.createdAt()
        );
        tradingHedgeLogMapper.insertTradingHedgeLog(toRecord(persisted));
        return persisted;
    }

    @Override
    public Optional<TradingHedgeLog> findLatestBySymbol(String symbol) {
        return Optional.ofNullable(toDomain(tradingHedgeLogMapper.selectLatestBySymbol(symbol)));
    }

    private TradingHedgeLogRecord toRecord(TradingHedgeLog hedgeLog) {
        return new TradingHedgeLogRecord(
                hedgeLog.hedgeLogId(),
                hedgeLog.symbol(),
                hedgeLog.positionId(),
                TradingMybatisSupport.toHedgeTriggerSourceCode(hedgeLog.triggerSource()),
                TradingMybatisSupport.toHedgeLogStatusCode(hedgeLog.actionStatus()),
                hedgeLog.netExposure(),
                hedgeLog.netExposureUsd(),
                hedgeLog.hedgeThresholdUsd(),
                hedgeLog.markPrice(),
                TradingMybatisSupport.toLocalDateTime(hedgeLog.priceTs()),
                hedgeLog.priceSource(),
                TradingMybatisSupport.toLocalDateTime(hedgeLog.createdAt())
        );
    }

    private TradingHedgeLog toDomain(TradingHedgeLogRecord record) {
        if (record == null) {
            return null;
        }
        return new TradingHedgeLog(
                record.id(),
                record.symbol(),
                record.positionId(),
                TradingMybatisSupport.toHedgeTriggerSource(record.triggerSourceCode()),
                TradingMybatisSupport.toHedgeLogStatus(record.actionStatusCode()),
                record.netExposure(),
                record.netExposureUsd(),
                record.hedgeThresholdUsd(),
                record.markPrice(),
                TradingMybatisSupport.toOffsetDateTime(record.priceTs()),
                record.priceSource(),
                TradingMybatisSupport.toOffsetDateTime(record.createdAt())
        );
    }
}
