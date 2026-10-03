package com.falconx.trading.repository;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.repository.mapper.TradingInboxMapper;
import com.falconx.trading.repository.mapper.record.TradingInboxRecord;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Repository;

/**
 * 交易 Inbox Repository 的 MyBatis 实现。
 *
 * <p>该实现通过 `INSERT IGNORE` 对低频关键事件做幂等去重，
 * SQL 统一落在 XML 中维护。
 */
@Repository
public class MybatisTradingInboxRepository implements TradingInboxRepository {

    private final TradingInboxMapper tradingInboxMapper;
    private final IdGenerator idGenerator;

    public MybatisTradingInboxRepository(TradingInboxMapper tradingInboxMapper, IdGenerator idGenerator) {
        this.tradingInboxMapper = tradingInboxMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public boolean markProcessedIfAbsent(String eventId, String eventType, OffsetDateTime processedAt) {
        return markProcessedIfAbsent(eventId, eventType, "wallet-or-market", "{}", processedAt);
    }

    @Override
    public boolean markProcessedIfAbsent(String eventId,
                                         String eventType,
                                         String source,
                                         String payloadJson,
                                         OffsetDateTime processedAt) {
        TradingInboxRecord record = new TradingInboxRecord(
                idGenerator.nextId(),
                eventId,
                eventType,
                source,
                payloadJson,
                1,
                TradingMybatisSupport.toLocalDateTime(processedAt),
                TradingMybatisSupport.toLocalDateTime(processedAt),
                TradingMybatisSupport.toLocalDateTime(processedAt)
        );
        return tradingInboxMapper.insertProcessedIfAbsent(record) == 1;
    }

    @Override
    public int deleteProcessedBefore(OffsetDateTime cutoff, int limit) {
        return tradingInboxMapper.deleteProcessedBefore(TradingMybatisSupport.toLocalDateTime(cutoff), limit);
    }
}
