package com.falconx.trading.engine;

import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 高频持仓快照索引测试。
 */
class InMemoryOpenPositionSnapshotStoreTests {

    @Test
    void shouldReturnCachedSortedSymbolSnapshotUntilPositionChanges() {
        InMemoryOpenPositionSnapshotStore store = new InMemoryOpenPositionSnapshotStore();
        TradingPosition later = position(2L, "BTCUSD", 1001L, OffsetDateTime.parse("2026-05-20T10:00:00Z"));
        TradingPosition earlier = position(1L, "BTCUSD", 1002L, OffsetDateTime.parse("2026-05-20T09:00:00Z"));
        store.replaceAll(List.of(later, earlier));

        List<TradingPosition> first = store.listOpenBySymbol("BTCUSD");
        List<TradingPosition> second = store.listOpenBySymbol("BTCUSD");

        Assertions.assertSame(first, second, "无持仓变更时应复用已排序 symbol 快照，避免每 tick 复制排序");
        Assertions.assertEquals(List.of(1L, 2L), first.stream().map(TradingPosition::positionId).toList());

        TradingPosition newest = position(3L, "BTCUSD", 1003L, OffsetDateTime.parse("2026-05-20T11:00:00Z"));
        store.upsert(newest);
        List<TradingPosition> afterUpsert = store.listOpenBySymbol("BTCUSD");

        Assertions.assertNotSame(first, afterUpsert);
        Assertions.assertEquals(List.of(1L, 2L, 3L), afterUpsert.stream().map(TradingPosition::positionId).toList());

        store.remove("BTCUSD", 2L);
        List<TradingPosition> afterRemove = store.listOpenBySymbol("BTCUSD");

        Assertions.assertEquals(List.of(1L, 3L), afterRemove.stream().map(TradingPosition::positionId).toList());
    }

    private static TradingPosition position(Long positionId, String symbol, Long userId, OffsetDateTime openedAt) {
        return new TradingPosition(
                positionId,
                positionId + 1000,
                userId,
                symbol,
                TradingOrderSide.BUY,
                BigDecimal.ONE,
                new BigDecimal("10000.00"),
                BigDecimal.ONE,
                new BigDecimal("0.005000"),
                1,
                BigDecimal.TEN,
                new BigDecimal("1000.00"),
                TradingMarginMode.ISOLATED,
                new BigDecimal("9000.00"),
                null,
                null,
                null,
                null,
                null,
                TradingPositionStatus.OPEN,
                BigDecimal.ZERO,
                "default",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                openedAt,
                null,
                openedAt
        );
    }
}
