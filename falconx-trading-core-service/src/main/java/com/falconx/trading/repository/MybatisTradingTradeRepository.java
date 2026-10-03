package com.falconx.trading.repository;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.entity.TradingTrade;
import com.falconx.trading.entity.TradingTradeType;
import com.falconx.trading.repository.mapper.TradingTradeMapper;
import com.falconx.trading.repository.mapper.record.TradingTradeRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 交易成交 Repository 的 MyBatis 实现。
 *
 * <p>该实现负责成交领域对象与 `t_trade` 记录之间的转换。
 */
@Repository
public class MybatisTradingTradeRepository implements TradingTradeRepository {

    private final TradingTradeMapper tradingTradeMapper;
    private final IdGenerator idGenerator;

    public MybatisTradingTradeRepository(TradingTradeMapper tradingTradeMapper, IdGenerator idGenerator) {
        this.tradingTradeMapper = tradingTradeMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public TradingTrade save(TradingTrade trade) {
        if (trade.tradeId() == null) {
            long id = idGenerator.nextId();
            TradingTrade persisted = new TradingTrade(
                    id,
                    trade.orderId(),
                    trade.positionId(),
                    trade.userId(),
                    trade.symbol(),
                    trade.side(),
                    trade.tradeType() == null ? TradingTradeType.OPEN : trade.tradeType(),
                    trade.quantity(),
                    trade.price(),
                    trade.fee(),
                    trade.realizedPnl(),
                    trade.tradedAt() == null ? OffsetDateTime.now() : trade.tradedAt()
            );
            tradingTradeMapper.insertTradingTrade(toRecord(persisted));
            return persisted;
        }
        return trade;
    }

    @Override
    public TradingTrade saveWithExplicitId(TradingTrade trade) {
        if (trade.tradeId() == null) {
            throw new IllegalArgumentException("saveWithExplicitId requires non-null tradeId");
        }
        tradingTradeMapper.insertTradingTrade(toRecord(trade));
        return trade;
    }

    @Override
    public Optional<TradingTrade> findOpenTradeByOrderId(Long orderId) {
        return Optional.ofNullable(toDomain(
                tradingTradeMapper.selectByOrderIdAndTradeType(
                        orderId,
                        TradingMybatisSupport.toTradeTypeCode(TradingTradeType.OPEN)
                )
        ));
    }

    @Override
    public Optional<TradingTrade> findByPositionIdAndTradeType(Long positionId, TradingTradeType tradeType) {
        return Optional.ofNullable(toDomain(
                tradingTradeMapper.selectByPositionIdAndTradeType(
                        positionId,
                        TradingMybatisSupport.toTradeTypeCode(Objects.requireNonNull(tradeType, "tradeType"))
                )
        ));
    }

    @Override
    public List<TradingTrade> findByUserIdPaginated(Long userId, int offset, int limit) {
        return tradingTradeMapper.selectByUserIdPaginated(userId, offset, limit)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countByUserId(Long userId) {
        return tradingTradeMapper.countByUserId(userId);
    }

    private TradingTradeRecord toRecord(TradingTrade trade) {
        return new TradingTradeRecord(
                trade.tradeId(),
                trade.orderId(),
                trade.positionId(),
                trade.userId(),
                trade.symbol(),
                TradingMybatisSupport.toSideCode(trade.side()),
                TradingMybatisSupport.toTradeTypeCode(trade.tradeType()),
                trade.quantity(),
                trade.price(),
                trade.fee(),
                trade.realizedPnl(),
                TradingMybatisSupport.toLocalDateTime(trade.tradedAt())
        );
    }

    private TradingTrade toDomain(TradingTradeRecord record) {
        if (record == null) {
            return null;
        }
        return new TradingTrade(
                record.id(),
                record.orderId(),
                record.positionId(),
                record.userId(),
                record.symbol(),
                TradingMybatisSupport.toSide(record.sideCode()),
                TradingMybatisSupport.toTradeType(record.tradeTypeCode()),
                record.quantity(),
                record.price(),
                record.fee(),
                record.realizedPnl(),
                TradingMybatisSupport.toOffsetDateTime(record.tradedAt())
        );
    }
}
