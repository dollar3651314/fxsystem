package com.falconx.market.service.impl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.falconx.market.entity.MarketTradingHoliday;
import com.falconx.market.entity.MarketTradingScheduleSnapshot;
import com.falconx.market.entity.MarketTradingSession;
import com.falconx.market.repository.MarketTradingScheduleSnapshotRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * market-service 交易时段保护测试。
 */
class DefaultMarketTradingScheduleGuardServiceTests {

    @Test
    void shouldBlockQuoteProcessingWhenHolidayFullyClosesMarket() {
        MarketTradingScheduleSnapshotRepository repository = mock(MarketTradingScheduleSnapshotRepository.class);
        MarketTradingScheduleSnapshot snapshot = new MarketTradingScheduleSnapshot(
                "BTCUSD",
                "CRYPTO",
                List.of(new MarketTradingSession(
                        1L,
                        "BTCUSD",
                        1,
                        1,
                        LocalTime.of(0, 0),
                        LocalTime.of(23, 59, 59),
                        "UTC",
                        true,
                        LocalDate.of(2026, 1, 1),
                        null
                )),
                List.of(),
                List.of(new MarketTradingHoliday(
                        1L,
                        "CRYPTO",
                        LocalDate.of(2026, 4, 20),
                        1,
                        null,
                        null,
                        "UTC",
                        "Holiday Close",
                        "INT"
                )),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        when(repository.findBySymbol("BTCUSD")).thenReturn(Optional.of(snapshot));
        DefaultMarketTradingScheduleGuardService service = new DefaultMarketTradingScheduleGuardService(repository);

        boolean allowed = service.isQuoteProcessingAllowed(
                "BTCUSD",
                OffsetDateTime.of(2026, 4, 20, 10, 0, 0, 0, ZoneOffset.UTC)
        );

        Assertions.assertFalse(allowed);
    }

    @Test
    void shouldAcceptQuoteProcessingWhenScheduleSnapshotIsMissing() {
        MarketTradingScheduleSnapshotRepository repository = mock(MarketTradingScheduleSnapshotRepository.class);
        when(repository.findBySymbol("BTCUSD")).thenReturn(Optional.empty());
        DefaultMarketTradingScheduleGuardService service = new DefaultMarketTradingScheduleGuardService(repository);

        Assertions.assertTrue(service.isQuoteProcessingAllowed("BTCUSD", OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @Test
    void shouldAcceptQuoteProcessingWhenSessionsAndExceptionsAreEmpty() {
        // 仓库返回了 snapshot 但 sessions/exceptions/holidays 都空 ——
        // 等价于 t_trading_hours 没配置，避免新增 market 静默 MARKET_CLOSED 把整市场报价丢弃。
        MarketTradingScheduleSnapshotRepository repository = mock(MarketTradingScheduleSnapshotRepository.class);
        MarketTradingScheduleSnapshot snapshot = new MarketTradingScheduleSnapshot(
                "WTCUSD",
                "ENERGY",
                List.of(),
                List.of(),
                List.of(),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        when(repository.findBySymbol("WTCUSD")).thenReturn(Optional.of(snapshot));
        DefaultMarketTradingScheduleGuardService service = new DefaultMarketTradingScheduleGuardService(repository);

        Assertions.assertTrue(service.isQuoteProcessingAllowed("WTCUSD", OffsetDateTime.now(ZoneOffset.UTC)));
    }
}
