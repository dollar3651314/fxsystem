package com.falconx.trading.repository;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.infrastructure.id.PublicIdentifierFormatter;
import com.falconx.trading.entity.TradingOrder;
import com.falconx.trading.repository.mapper.TradingOrderMapper;
import com.falconx.trading.repository.mapper.record.TradingOrderRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 交易订单 Repository 的 MyBatis 实现。
 *
 * <p>该实现负责订单领域对象与 `t_order` 持久化记录之间的转换。
 */
@Repository
public class MybatisTradingOrderRepository implements TradingOrderRepository {

    private final TradingOrderMapper tradingOrderMapper;
    private final IdGenerator idGenerator;

    public MybatisTradingOrderRepository(TradingOrderMapper tradingOrderMapper, IdGenerator idGenerator) {
        this.tradingOrderMapper = tradingOrderMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public TradingOrder save(TradingOrder order) {
        if (order.orderId() == null) {
            long id = idGenerator.nextId();
            OffsetDateTime now = order.createdAt() == null ? OffsetDateTime.now() : order.createdAt();
            TradingOrder persisted = new TradingOrder(
                    id,
                    PublicIdentifierFormatter.orderNo(id),
                    order.userId(),
                    order.symbol(),
                    order.side(),
                    order.orderType(),
                    order.quantity(),
                    order.requestedPrice(),
                    order.filledPrice(),
                    order.leverage(),
                    order.margin(),
                    order.fee(),
                    order.openFeeRate(),
                    order.clientOrderId(),
                    order.status(),
                    order.rejectReason(),
                    now,
                    order.updatedAt() == null ? now : order.updatedAt()
            );
            tradingOrderMapper.insertTradingOrder(toRecord(persisted));
            return persisted;
        }

        TradingOrder persisted = new TradingOrder(
                order.orderId(),
                order.orderNo(),
                order.userId(),
                order.symbol(),
                order.side(),
                order.orderType(),
                order.quantity(),
                order.requestedPrice(),
                order.filledPrice(),
                order.leverage(),
                order.margin(),
                order.fee(),
                order.openFeeRate(),
                order.clientOrderId(),
                order.status(),
                order.rejectReason(),
                order.createdAt(),
                order.updatedAt() == null ? OffsetDateTime.now() : order.updatedAt()
        );
        tradingOrderMapper.updateTradingOrder(toRecord(persisted));
        return persisted;
    }

    @Override
    public Optional<TradingOrder> findByUserIdAndClientOrderId(Long userId, String clientOrderId) {
        return Optional.ofNullable(toDomain(
                tradingOrderMapper.selectByUserIdAndClientOrderId(userId, clientOrderId)
        ));
    }

    @Override
    public List<TradingOrder> findByUserIdPaginated(Long userId, int offset, int limit) {
        return tradingOrderMapper.selectByUserIdPaginated(userId, offset, limit)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countByUserId(Long userId) {
        return tradingOrderMapper.countByUserId(userId);
    }

    private TradingOrderRecord toRecord(TradingOrder order) {
        return new TradingOrderRecord(
                order.orderId(),
                order.orderNo(),
                order.userId(),
                order.symbol(),
                TradingMybatisSupport.toSideCode(order.side()),
                TradingMybatisSupport.toOrderTypeCode(order.orderType()),
                order.quantity(),
                order.requestedPrice(),
                order.filledPrice(),
                order.leverage(),
                order.margin(),
                order.fee(),
                order.openFeeRate(),
                order.clientOrderId(),
                TradingMybatisSupport.toOrderStatusCode(order.status()),
                order.rejectReason(),
                TradingMybatisSupport.toLocalDateTime(order.createdAt()),
                TradingMybatisSupport.toLocalDateTime(order.updatedAt())
        );
    }

    private TradingOrder toDomain(TradingOrderRecord record) {
        if (record == null) {
            return null;
        }
        return new TradingOrder(
                record.id(),
                record.orderNo(),
                record.userId(),
                record.symbol(),
                TradingMybatisSupport.toSide(record.sideCode()),
                TradingMybatisSupport.toOrderType(record.orderTypeCode()),
                record.quantity(),
                record.requestedPrice(),
                record.filledPrice(),
                record.leverage(),
                record.margin(),
                record.fee(),
                record.openFeeRate(),
                record.clientOrderId(),
                TradingMybatisSupport.toOrderStatus(record.statusCode()),
                record.rejectReason(),
                TradingMybatisSupport.toOffsetDateTime(record.createdAt()),
                TradingMybatisSupport.toOffsetDateTime(record.updatedAt())
        );
    }
}
