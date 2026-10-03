package com.falconx.trading.repository;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.repository.mapper.TradingPositionMapper;
import com.falconx.trading.repository.mapper.record.TradingPositionRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 交易持仓 Repository 的 MyBatis 实现。
 *
 * <p>该实现负责把一单一仓模型落到 `t_position`，
 * 并保持 `openingOrderId` 作为最小查询键。
 */
@Repository
public class MybatisTradingPositionRepository implements TradingPositionRepository {

    private final TradingPositionMapper tradingPositionMapper;
    private final IdGenerator idGenerator;

    public MybatisTradingPositionRepository(TradingPositionMapper tradingPositionMapper, IdGenerator idGenerator) {
        this.tradingPositionMapper = tradingPositionMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public TradingPosition save(TradingPosition position) {
        if (position.positionId() == null) {
            long id = idGenerator.nextId();
            OffsetDateTime openedAt = position.openedAt() == null ? OffsetDateTime.now() : position.openedAt();
            TradingPosition persisted = new TradingPosition(
                    id,
                    position.openingOrderId(),
                    position.userId(),
                    position.symbol(),
                    position.side(),
                    position.quantity(),
                    position.entryPrice(),
                    position.entryFxRate(),
                    position.mmRateAtOpen(),
                    position.tierNoAtOpen(),
                    position.leverage(),
                    position.margin(),
                    position.marginMode() == null ? TradingMarginMode.ISOLATED : position.marginMode(),
                    position.liquidationPrice(),
                    position.takeProfitPrice(),
                    position.stopLossPrice(),
                    position.closePrice(),
                    position.closeReason(),
                    position.realizedPnl(),
                    position.status(),
                    position.openFeeRate(),
                    position.groupCodeAtOpen() == null ? "default" : position.groupCodeAtOpen(),
                    position.bidExtraAtOpen() == null ? java.math.BigDecimal.ZERO : position.bidExtraAtOpen(),
                    position.askExtraAtOpen() == null ? java.math.BigDecimal.ZERO : position.askExtraAtOpen(),
                    openedAt,
                    position.closedAt(),
                    position.updatedAt() == null ? openedAt : position.updatedAt()
            );
            tradingPositionMapper.insertTradingPosition(toRecord(persisted));
            return persisted;
        }

        TradingPosition persisted = new TradingPosition(
                position.positionId(),
                position.openingOrderId(),
                position.userId(),
                position.symbol(),
                position.side(),
                position.quantity(),
                position.entryPrice(),
                position.entryFxRate(),
                position.mmRateAtOpen(),
                position.tierNoAtOpen(),
                position.leverage(),
                position.margin(),
                position.marginMode() == null ? TradingMarginMode.ISOLATED : position.marginMode(),
                position.liquidationPrice(),
                position.takeProfitPrice(),
                position.stopLossPrice(),
                position.closePrice(),
                position.closeReason(),
                position.realizedPnl(),
                position.status(),
                position.openFeeRate(),
                position.groupCodeAtOpen() == null ? "default" : position.groupCodeAtOpen(),
                position.bidExtraAtOpen() == null ? java.math.BigDecimal.ZERO : position.bidExtraAtOpen(),
                position.askExtraAtOpen() == null ? java.math.BigDecimal.ZERO : position.askExtraAtOpen(),
                position.openedAt(),
                position.closedAt(),
                position.updatedAt() == null ? OffsetDateTime.now() : position.updatedAt()
        );
        tradingPositionMapper.updateTradingPosition(toRecord(persisted));
        return persisted;
    }

    @Override
    public Optional<TradingPosition> findByOpeningOrderId(Long openingOrderId) {
        return Optional.ofNullable(toDomain(tradingPositionMapper.selectByOpeningOrderId(openingOrderId)));
    }

    @Override
    public Optional<TradingPosition> findByIdAndUserIdForUpdate(Long positionId, Long userId) {
        return Optional.ofNullable(toDomain(tradingPositionMapper.selectByIdAndUserIdForUpdate(positionId, userId)));
    }

    @Override
    public Optional<TradingPosition> findByIdForUpdate(Long positionId) {
        return Optional.ofNullable(toDomain(tradingPositionMapper.selectByIdForUpdate(positionId)));
    }

    @Override
    public List<TradingPosition> findOpenByUserId(Long userId) {
        return tradingPositionMapper.selectOpenByUserId(userId)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countOpenByUserIdAndSymbol(Long userId, String symbol) {
        return tradingPositionMapper.countOpenByUserIdAndSymbol(userId, symbol);
    }

    @Override
    public long countOpenBySymbol(String symbol) {
        return tradingPositionMapper.countOpenBySymbol(symbol);
    }

    @Override
    public List<TradingPosition> findAllOpenPositions() {
        return tradingPositionMapper.selectAllOpenPositions()
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<TradingPosition> findByUserIdPaginated(Long userId,
                                                       List<TradingPositionStatus> statusFilter,
                                                       int offset,
                                                       int limit) {
        return tradingPositionMapper.selectByUserIdPaginated(userId, toStatusCodes(statusFilter), offset, limit)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countByUserId(Long userId, List<TradingPositionStatus> statusFilter) {
        return tradingPositionMapper.countByUserId(userId, toStatusCodes(statusFilter));
    }

    private static List<Integer> toStatusCodes(List<TradingPositionStatus> statusFilter) {
        if (statusFilter == null || statusFilter.isEmpty()) return null;
        return statusFilter.stream().map(TradingMybatisSupport::toPositionStatusCode).toList();
    }

    @Override
    public List<Long> findDistinctUserIdsBySymbolOpen(String symbol) {
        return tradingPositionMapper.selectDistinctUserIdsBySymbolOpen(symbol);
    }

    private TradingPositionRecord toRecord(TradingPosition position) {
        return new TradingPositionRecord(
                position.positionId(),
                position.openingOrderId(),
                position.userId(),
                position.symbol(),
                TradingMybatisSupport.toSideCode(position.side()),
                position.quantity(),
                position.entryPrice(),
                position.entryFxRate(),
                position.mmRateAtOpen(),
                position.tierNoAtOpen(),
                position.leverage(),
                position.margin(),
                TradingMybatisSupport.toMarginModeCode(position.marginMode()),
                position.liquidationPrice(),
                position.takeProfitPrice(),
                position.stopLossPrice(),
                position.closePrice(),
                TradingMybatisSupport.toCloseReasonCode(position.closeReason()),
                position.realizedPnl(),
                TradingMybatisSupport.toPositionStatusCode(position.status()),
                position.openFeeRate(),
                position.groupCodeAtOpen() == null ? "default" : position.groupCodeAtOpen(),
                position.bidExtraAtOpen() == null ? java.math.BigDecimal.ZERO : position.bidExtraAtOpen(),
                position.askExtraAtOpen() == null ? java.math.BigDecimal.ZERO : position.askExtraAtOpen(),
                TradingMybatisSupport.toLocalDateTime(position.openedAt()),
                TradingMybatisSupport.toLocalDateTime(position.closedAt()),
                TradingMybatisSupport.toLocalDateTime(position.updatedAt())
        );
    }

    private TradingPosition toDomain(TradingPositionRecord record) {
        if (record == null) {
            return null;
        }
        return new TradingPosition(
                record.id(),
                record.openingOrderId(),
                record.userId(),
                record.symbol(),
                TradingMybatisSupport.toSide(record.sideCode()),
                record.quantity(),
                record.entryPrice(),
                record.entryFxRate(),
                record.mmRateAtOpen(),
                record.tierNoAtOpen(),
                record.leverage(),
                record.margin(),
                TradingMybatisSupport.toMarginMode(record.marginModeCode()),
                record.liquidationPrice(),
                record.takeProfitPrice(),
                record.stopLossPrice(),
                record.closePrice(),
                TradingMybatisSupport.toCloseReason(record.closeReasonCode()),
                record.realizedPnl(),
                TradingMybatisSupport.toPositionStatus(record.statusCode()),
                record.openFeeRate(),
                record.groupCodeAtOpen() == null ? "default" : record.groupCodeAtOpen(),
                record.bidExtraAtOpen() == null ? java.math.BigDecimal.ZERO : record.bidExtraAtOpen(),
                record.askExtraAtOpen() == null ? java.math.BigDecimal.ZERO : record.askExtraAtOpen(),
                TradingMybatisSupport.toOffsetDateTime(record.openedAt()),
                TradingMybatisSupport.toOffsetDateTime(record.closedAt()),
                TradingMybatisSupport.toOffsetDateTime(record.updatedAt())
        );
    }
}
