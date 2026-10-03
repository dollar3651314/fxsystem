package com.falconx.trading.service.impl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.falconx.trading.repository.TradingScheduleSnapshotRepository;
import com.falconx.trading.service.model.TradingHolidayRule;
import com.falconx.trading.service.model.TradingHoursExceptionRule;
import com.falconx.trading.service.model.TradingScheduleSnapshot;
import com.falconx.trading.service.model.TradingSessionWindow;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * DefaultTradingScheduleService 的规则测试。
 *
 * <p>该测试专门覆盖 CFD 设计补充事项里列出的交易时间关键场景，
 * 防止后续修改后再次退回到“只支持普通日盘”的错误实现。
 */
class DefaultTradingScheduleServiceTests {

    @Test
    void shouldRejectWhenScheduleSnapshotIsMissing() {
        TradingScheduleSnapshotRepository repository = mock(TradingScheduleSnapshotRepository.class);
        when(repository.findBySymbol("BTCUSDT")).thenReturn(Optional.empty());
        DefaultTradingScheduleService service = new DefaultTradingScheduleService(repository);

        boolean tradable = service.isTradable("BTCUSDT", OffsetDateTime.now(ZoneOffset.UTC));

        Assertions.assertFalse(tradable);
    }

    @Test
    void shouldRejectWhenHolidayEarlyCloseAlreadyReached() {
        TradingScheduleSnapshotRepository repository = mock(TradingScheduleSnapshotRepository.class);
        TradingScheduleSnapshot snapshot = new TradingScheduleSnapshot(
                "SPX500",
                "US_INDEX",
                List.of(new TradingSessionWindow(
                        5,
                        1,
                        LocalTime.of(13, 30),
                        LocalTime.of(20, 0),
                        "UTC",
                        true,
                        LocalDate.of(2026, 1, 1),
                        null
                )),
                List.of(),
                List.of(new TradingHolidayRule(
                        "US_INDEX",
                        LocalDate.of(2026, 4, 17),
                        2,
                        null,
                        LocalTime.of(18, 0),
                        "UTC",
                        "Early Close",
                        "US"
                )),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        when(repository.findBySymbol("SPX500")).thenReturn(Optional.of(snapshot));
        DefaultTradingScheduleService service = new DefaultTradingScheduleService(repository);

        boolean tradable = service.isTradable("SPX500", OffsetDateTime.of(2026, 4, 17, 18, 30, 0, 0, ZoneOffset.UTC));

        Assertions.assertFalse(tradable);
    }

    @Test
    void shouldRejectBeforeHolidayLateOpenAndAllowAfterIt() {
        TradingScheduleSnapshotRepository repository = mock(TradingScheduleSnapshotRepository.class);
        TradingScheduleSnapshot snapshot = new TradingScheduleSnapshot(
                "XAUUSD",
                "METAL",
                List.of(new TradingSessionWindow(
                        1,
                        1,
                        LocalTime.of(1, 0),
                        LocalTime.of(23, 0),
                        "UTC",
                        true,
                        LocalDate.of(2026, 1, 1),
                        null
                )),
                List.of(),
                List.of(new TradingHolidayRule(
                        "METAL",
                        LocalDate.of(2026, 4, 20),
                        3,
                        LocalTime.of(10, 0),
                        null,
                        "UTC",
                        "Late Open",
                        "INT"
                )),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        when(repository.findBySymbol("XAUUSD")).thenReturn(Optional.of(snapshot));
        DefaultTradingScheduleService service = new DefaultTradingScheduleService(repository);

        boolean blockedBeforeOpen = service.isTradable("XAUUSD", OffsetDateTime.of(2026, 4, 20, 9, 30, 0, 0, ZoneOffset.UTC));
        boolean allowedAfterOpen = service.isTradable("XAUUSD", OffsetDateTime.of(2026, 4, 20, 10, 30, 0, 0, ZoneOffset.UTC));

        Assertions.assertFalse(blockedBeforeOpen);
        Assertions.assertTrue(allowedAfterOpen);
    }

    @Test
    void shouldAllowSecondWindowOfMultiSessionTradingDay() {
        TradingScheduleSnapshotRepository repository = mock(TradingScheduleSnapshotRepository.class);
        TradingScheduleSnapshot snapshot = new TradingScheduleSnapshot(
                "EURUSD",
                "FX",
                List.of(
                        new TradingSessionWindow(1, 1, LocalTime.of(9, 0), LocalTime.of(12, 0), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(1, 2, LocalTime.of(13, 0), LocalTime.of(17, 0), "UTC", true, LocalDate.of(2026, 1, 1), null)
                ),
                List.of(),
                List.of(),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        when(repository.findBySymbol("EURUSD")).thenReturn(Optional.of(snapshot));
        DefaultTradingScheduleService service = new DefaultTradingScheduleService(repository);

        boolean tradable = service.isTradable("EURUSD", OffsetDateTime.of(2026, 4, 20, 14, 0, 0, 0, ZoneOffset.UTC));

        Assertions.assertTrue(tradable);
    }

    @Test
    void shouldAllowWeeklySessionThatSpansAcrossMidnight() {
        TradingScheduleSnapshotRepository repository = mock(TradingScheduleSnapshotRepository.class);
        TradingScheduleSnapshot snapshot = new TradingScheduleSnapshot(
                "BTCUSDT",
                "CRYPTO",
                List.of(new TradingSessionWindow(
                        1,
                        1,
                        LocalTime.of(22, 0),
                        LocalTime.of(4, 0),
                        "UTC",
                        true,
                        LocalDate.of(2026, 1, 1),
                        null
                )),
                List.of(),
                List.of(),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        when(repository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(snapshot));
        DefaultTradingScheduleService service = new DefaultTradingScheduleService(repository);

        boolean tradable = service.isTradable("BTCUSDT", OffsetDateTime.of(2026, 4, 21, 2, 0, 0, 0, ZoneOffset.UTC));

        Assertions.assertTrue(tradable);
    }

    @Test
    void shouldAllowExceptionSessionThatSpansAcrossMidnight() {
        TradingScheduleSnapshotRepository repository = mock(TradingScheduleSnapshotRepository.class);
        TradingScheduleSnapshot snapshot = new TradingScheduleSnapshot(
                "ETHUSDT",
                "CRYPTO",
                List.of(),
                List.of(new TradingHoursExceptionRule(
                        LocalDate.of(2026, 4, 20),
                        2,
                        1,
                        LocalTime.of(22, 0),
                        LocalTime.of(4, 0),
                        "UTC",
                        "special-night-session"
                )),
                List.of(),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        when(repository.findBySymbol("ETHUSDT")).thenReturn(Optional.of(snapshot));
        DefaultTradingScheduleService service = new DefaultTradingScheduleService(repository);

        boolean tradable = service.isTradable("ETHUSDT", OffsetDateTime.of(2026, 4, 21, 2, 0, 0, 0, ZoneOffset.UTC));

        Assertions.assertTrue(tradable);
    }

    @Test
    void shouldBlockManualCloseWhenHolidayFullCloseBlocksNewOpen() {
        TradingScheduleSnapshotRepository repository = mock(TradingScheduleSnapshotRepository.class);
        TradingScheduleSnapshot snapshot = new TradingScheduleSnapshot(
                "BTCUSDT",
                "CRYPTO",
                List.of(new TradingSessionWindow(
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
                List.of(new TradingHolidayRule(
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
        when(repository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(snapshot));
        DefaultTradingScheduleService service = new DefaultTradingScheduleService(repository);

        boolean openAllowed = service.isOpenAllowed("BTCUSDT", OffsetDateTime.of(2026, 4, 20, 10, 0, 0, 0, ZoneOffset.UTC));
        boolean closeAllowed = service.isCloseAllowed("BTCUSDT", OffsetDateTime.of(2026, 4, 20, 10, 0, 0, 0, ZoneOffset.UTC));

        Assertions.assertFalse(openAllowed);
        Assertions.assertFalse(closeAllowed);
    }

    @Test
    void shouldBlockLiquidationWhenHolidayFullCloseBlocksNewOpen() {
        TradingScheduleSnapshotRepository repository = mock(TradingScheduleSnapshotRepository.class);
        TradingScheduleSnapshot snapshot = new TradingScheduleSnapshot(
                "BTCUSDT",
                "CRYPTO",
                List.of(new TradingSessionWindow(
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
                List.of(new TradingHolidayRule(
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
        when(repository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(snapshot));
        DefaultTradingScheduleService service = new DefaultTradingScheduleService(repository);

        boolean openAllowed = service.isOpenAllowed("BTCUSDT", OffsetDateTime.of(2026, 4, 20, 10, 0, 0, 0, ZoneOffset.UTC));
        boolean liquidationAllowed = service.isCloseAllowed("BTCUSDT", OffsetDateTime.of(2026, 4, 20, 10, 0, 0, 0, ZoneOffset.UTC));

        Assertions.assertFalse(openAllowed);
        Assertions.assertFalse(liquidationAllowed);
    }
}
