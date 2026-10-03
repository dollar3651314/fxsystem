package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPendingOrderStatus;
import com.falconx.trading.entity.TradingPendingOrderTrigger;
import com.falconx.trading.entity.TradingPendingOrderTriggerKind;
import com.falconx.trading.entity.TradingPendingOrderType;
import com.falconx.trading.repository.mapper.TradingPendingOrderTriggerMapper;
import com.falconx.trading.repository.mapper.record.TradingPendingOrderTriggerRecord;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * STAGE-3-PENDING-ORDER：挂单触发仓储 MyBatis 实现。
 */
@Repository
public class MybatisTradingPendingOrderTriggerRepository implements TradingPendingOrderTriggerRepository {

    private final TradingPendingOrderTriggerMapper mapper;

    public MybatisTradingPendingOrderTriggerRepository(TradingPendingOrderTriggerMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(TradingPendingOrderTrigger order) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TradingPendingOrderTriggerRecord record = new TradingPendingOrderTriggerRecord(
                order.id(),
                order.orderNo(),
                order.userId(),
                order.groupCodeAtCreate() == null ? "default" : order.groupCodeAtCreate(),
                order.bidExtraAtCreate() == null ? BigDecimal.ZERO : order.bidExtraAtCreate(),
                order.askExtraAtCreate() == null ? BigDecimal.ZERO : order.askExtraAtCreate(),
                order.symbol(),
                order.orderType().code(),
                TradingMybatisSupport.toSideCode(order.side()),
                order.quantity(),
                order.triggerPrice(),
                order.limitPrice(),
                order.leverage(),
                order.marginMode() == null ? null : (order.marginMode() == TradingMarginMode.CROSS ? 1 : 2),
                order.frozenMargin() == null ? BigDecimal.ZERO : order.frozenMargin(),
                order.frozenFee() == null ? BigDecimal.ZERO : order.frozenFee(),
                order.status().code(),
                order.parentPositionId(),
                order.triggerKind() == null ? null : order.triggerKind().code(),
                order.clientOrderId(),
                order.triggeredOrderId(),
                null, null, null,
                now, now
        );
        mapper.insert(record);
    }

    @Override
    public Optional<TradingPendingOrderTrigger> findById(Long id) {
        return Optional.ofNullable(toDomain(mapper.selectById(id)));
    }

    @Override
    public Optional<TradingPendingOrderTrigger> findByIdForUpdate(Long id) {
        return Optional.ofNullable(toDomain(mapper.selectByIdForUpdate(id)));
    }

    @Override
    public List<TradingPendingOrderTrigger> findPendingBySymbol(String symbol) {
        return mapper.selectPendingBySymbol(symbol).stream().map(this::toDomain).toList();
    }

    @Override
    public List<String> findPendingSymbols() {
        return mapper.selectPendingSymbols();
    }

    @Override
    public long countPendingBySymbol(String symbol) {
        return mapper.countPendingBySymbol(symbol);
    }

    @Override
    public List<TradingPendingOrderTrigger> findUserOpeningPending(Long userId, String symbol,
                                                                    Integer statusCode, int offset, int limit) {
        return mapper.selectUserOpeningPending(userId, symbol, statusCode, offset, limit)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public long countUserOpeningPending(Long userId, String symbol, Integer statusCode) {
        return mapper.countUserOpeningPending(userId, symbol, statusCode);
    }

    @Override
    public List<TradingPendingOrderTrigger> findSlTpByPositionId(Long positionId) {
        return mapper.selectSlTpByPositionId(positionId).stream().map(this::toDomain).toList();
    }

    @Override
    public List<TradingPendingOrderTrigger> findAdminPaginated(Long userId, String symbol, Integer statusCode,
                                                               boolean includeSlTp, int offset, int limit) {
        return mapper.selectAdminPaginated(userId, symbol, statusCode, includeSlTp, offset, limit)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public long countAdminFiltered(Long userId, String symbol, Integer statusCode, boolean includeSlTp) {
        return mapper.countAdminFiltered(userId, symbol, statusCode, includeSlTp);
    }

    @Override
    public int markTriggered(Long id, Long triggeredOrderId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return mapper.updateStatusAtomic(id,
                TradingPendingOrderStatus.PENDING.code(),
                TradingPendingOrderStatus.TRIGGERED.code(),
                triggeredOrderId, now, null, null, now);
    }

    @Override
    public int markCancelled(Long id, String cancelReason) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return mapper.updateStatusAtomic(id,
                TradingPendingOrderStatus.PENDING.code(),
                TradingPendingOrderStatus.CANCELLED.code(),
                null, null, now, cancelReason, now);
    }

    @Override
    public int markRejected(Long id, String cancelReason) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return mapper.updateStatusAtomic(id,
                TradingPendingOrderStatus.PENDING.code(),
                TradingPendingOrderStatus.REJECTED.code(),
                null, null, now, cancelReason, now);
    }

    @Override
    public int updateTriggerPrices(Long id, BigDecimal triggerPrice, BigDecimal limitPrice, BigDecimal quantity) {
        return mapper.updateTriggerPrices(id, triggerPrice, limitPrice, quantity, LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    public int cancelSlTpByPositionId(Long positionId, int triggerKindCode, String cancelReason) {
        return mapper.cancelSlTpByPositionId(positionId, triggerKindCode, cancelReason,
                LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    public int cancelAllSlTpByPositionId(Long positionId, String cancelReason) {
        return mapper.cancelAllSlTpByPositionId(positionId, cancelReason, LocalDateTime.now(ZoneOffset.UTC));
    }

    private TradingPendingOrderTrigger toDomain(TradingPendingOrderTriggerRecord r) {
        if (r == null) return null;
        return new TradingPendingOrderTrigger(
                r.id(),
                r.orderNo(),
                r.userId(),
                r.groupCodeAtCreate() == null ? "default" : r.groupCodeAtCreate(),
                r.bidExtraAtCreate() == null ? BigDecimal.ZERO : r.bidExtraAtCreate(),
                r.askExtraAtCreate() == null ? BigDecimal.ZERO : r.askExtraAtCreate(),
                r.symbol(),
                TradingPendingOrderType.fromCode(r.orderType()),
                TradingMybatisSupport.toSide(r.side()),
                r.quantity(),
                r.triggerPrice(),
                r.limitPrice(),
                r.leverage(),
                r.marginMode() == null ? null : (r.marginMode() == 1 ? TradingMarginMode.CROSS : TradingMarginMode.ISOLATED),
                r.frozenMargin(),
                r.frozenFee(),
                TradingPendingOrderStatus.fromCode(r.status()),
                r.parentPositionId(),
                TradingPendingOrderTriggerKind.fromCode(r.triggerKind()),
                r.clientOrderId(),
                r.triggeredOrderId(),
                TradingMybatisSupport.toOffsetDateTime(r.triggeredAt()),
                TradingMybatisSupport.toOffsetDateTime(r.cancelledAt()),
                r.cancelReason(),
                TradingMybatisSupport.toOffsetDateTime(r.createdAt()),
                TradingMybatisSupport.toOffsetDateTime(r.updatedAt())
        );
    }
}
