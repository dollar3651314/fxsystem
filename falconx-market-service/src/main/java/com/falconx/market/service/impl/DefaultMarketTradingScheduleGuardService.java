package com.falconx.market.service.impl;

import com.falconx.market.entity.MarketTradingHoliday;
import com.falconx.market.entity.MarketTradingScheduleSnapshot;
import com.falconx.market.entity.MarketTradingSession;
import com.falconx.market.entity.MarketTradingSessionException;
import com.falconx.market.repository.MarketTradingScheduleSnapshotRepository;
import com.falconx.market.service.MarketTradingScheduleGuardService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * market-service 交易时段保护实现。
 *
 * <p>休盘时不处理 LP tick，避免 Redis、Kafka、ClickHouse 和北向 WS 出现可成交错觉。
 */
@Service
public class DefaultMarketTradingScheduleGuardService implements MarketTradingScheduleGuardService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMarketTradingScheduleGuardService.class);

    private static final int EXCEPTION_FULL_CLOSE = 1;
    private static final int EXCEPTION_SPECIAL_SESSION = 2;
    private static final int HOLIDAY_FULL_CLOSE = 1;
    private static final int HOLIDAY_EARLY_CLOSE = 2;
    private static final int HOLIDAY_LATE_OPEN = 3;

    private final MarketTradingScheduleSnapshotRepository scheduleSnapshotRepository;

    public DefaultMarketTradingScheduleGuardService(MarketTradingScheduleSnapshotRepository scheduleSnapshotRepository) {
        this.scheduleSnapshotRepository = scheduleSnapshotRepository;
    }

    @Override
    public boolean isQuoteProcessingAllowed(String symbol, OffsetDateTime now) {
        MarketTradingScheduleSnapshot snapshot = scheduleSnapshotRepository.findBySymbol(symbol).orElse(null);
        if (snapshot == null) {
            log.warn("market.trading.schedule.snapshot.missing symbol={} action=accept", symbol);
            return true;
        }
        // "snapshot 已生成但 sessions/exceptions 都空" 与 "snapshot 完全缺失" 语义一致 ——
        // 都表示 t_trading_hours 配置缺失，应 fail-open 让 LP 报价能落库（避免新增 market
        // 配置遗漏时全市场静默 MARKET_CLOSED）；闭市真要拦截必须配置显式 session 或 exception。
        if (snapshot.sessions().isEmpty() && snapshot.exceptions().isEmpty()) {
            log.warn("market.trading.schedule.sessions.unconfigured symbol={} marketCode={} action=accept",
                    symbol,
                    snapshot.marketCode());
            return true;
        }
        List<MarketTradingSessionException> matchingExceptions = snapshot.exceptions().stream()
                .filter(rule -> matchesExceptionDate(now, rule))
                .sorted(Comparator.comparing(MarketTradingSessionException::sessionNo,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (matchingExceptions.stream().anyMatch(rule -> rule.exceptionType() == EXCEPTION_FULL_CLOSE)) {
            log.info("market.quote.processing.blocked symbol={} reason=exception-full-close", symbol);
            return false;
        }
        List<MarketTradingSessionException> specialSessions = matchingExceptions.stream()
                .filter(rule -> rule.exceptionType() == EXCEPTION_SPECIAL_SESSION)
                .toList();
        if (!specialSessions.isEmpty()) {
            boolean tradable = specialSessions.stream().anyMatch(rule -> isExceptionSessionActive(now, rule));
            log.info("market.quote.processing.evaluated symbol={} source=exception tradable={}", symbol, tradable);
            return tradable;
        }

        List<MarketTradingSession> baseSessions = snapshot.sessions().stream()
                .filter(MarketTradingSession::enabled)
                .sorted(Comparator.comparingInt(MarketTradingSession::sessionNo))
                .toList();
        MarketTradingHoliday holidayRule = snapshot.holidays().stream()
                .filter(rule -> matchesDate(now, rule.holidayDate(), rule.timezone()))
                .findFirst()
                .orElse(null);
        if (holidayRule != null) {
            if (holidayRule.holidayType() == HOLIDAY_FULL_CLOSE) {
                log.info("market.quote.processing.blocked symbol={} reason=holiday-full-close marketCode={}",
                        symbol,
                        snapshot.marketCode());
                return false;
            }
            baseSessions = applyHoliday(baseSessions, holidayRule);
        }

        boolean tradable = baseSessions.stream().anyMatch(rule -> isSessionActive(now, rule));
        log.info("market.quote.processing.evaluated symbol={} source=weekly tradable={} sessions={}",
                symbol,
                tradable,
                baseSessions.size());
        return tradable;
    }

    private boolean matchesDate(OffsetDateTime now, LocalDate tradeDate, String timezone) {
        return now.atZoneSameInstant(ZoneId.of(timezone)).toLocalDate().equals(tradeDate);
    }

    private boolean matchesExceptionDate(OffsetDateTime now, MarketTradingSessionException rule) {
        ZonedDateTime zonedNow = now.atZoneSameInstant(ZoneId.of(rule.timezone()));
        LocalDate currentDate = zonedNow.toLocalDate();
        if (currentDate.equals(rule.tradeDate())) {
            return true;
        }
        return rule.openTime() != null
                && rule.closeTime() != null
                && spansOvernight(rule.openTime(), rule.closeTime())
                && currentDate.equals(rule.tradeDate().plusDays(1));
    }

    private boolean isExceptionSessionActive(OffsetDateTime now, MarketTradingSessionException rule) {
        if (rule.openTime() == null || rule.closeTime() == null) {
            return false;
        }
        ZonedDateTime zonedNow = now.atZoneSameInstant(ZoneId.of(rule.timezone()));
        LocalDate currentDate = zonedNow.toLocalDate();
        LocalTime currentTime = zonedNow.toLocalTime();
        if (!spansOvernight(rule.openTime(), rule.closeTime())) {
            return currentDate.equals(rule.tradeDate()) && withinWindow(currentTime, rule.openTime(), rule.closeTime());
        }
        return (currentDate.equals(rule.tradeDate()) && !currentTime.isBefore(rule.openTime()))
                || (currentDate.equals(rule.tradeDate().plusDays(1)) && currentTime.isBefore(rule.closeTime()));
    }

    private boolean isSessionActive(OffsetDateTime now, MarketTradingSession rule) {
        ZonedDateTime zonedNow = now.atZoneSameInstant(ZoneId.of(rule.timezone()));
        LocalDate anchorDate = resolveAnchorDate(zonedNow, rule);
        if (anchorDate == null) {
            return false;
        }
        return withinEffectiveRange(anchorDate, rule.effectiveFrom(), rule.effectiveTo())
                && withinWindow(zonedNow.toLocalTime(), rule.openTime(), rule.closeTime());
    }

    private LocalDate resolveAnchorDate(ZonedDateTime zonedNow, MarketTradingSession rule) {
        LocalTime currentTime = zonedNow.toLocalTime();
        if (!spansOvernight(rule.openTime(), rule.closeTime())) {
            return zonedNow.getDayOfWeek().getValue() == rule.dayOfWeek() ? zonedNow.toLocalDate() : null;
        }
        if (zonedNow.getDayOfWeek().getValue() == rule.dayOfWeek() && !currentTime.isBefore(rule.openTime())) {
            return zonedNow.toLocalDate();
        }
        ZonedDateTime previousDay = zonedNow.minusDays(1);
        if (previousDay.getDayOfWeek().getValue() == rule.dayOfWeek() && currentTime.isBefore(rule.closeTime())) {
            return previousDay.toLocalDate();
        }
        return null;
    }

    private boolean withinEffectiveRange(LocalDate anchorDate, LocalDate effectiveFrom, LocalDate effectiveTo) {
        return !anchorDate.isBefore(effectiveFrom) && (effectiveTo == null || !anchorDate.isAfter(effectiveTo));
    }

    private boolean withinWindow(LocalTime current, LocalTime openTime, LocalTime closeTime) {
        if (openTime.equals(closeTime)) {
            return false;
        }
        if (spansOvernight(openTime, closeTime)) {
            return !current.isBefore(openTime) || current.isBefore(closeTime);
        }
        return !current.isBefore(openTime) && current.isBefore(closeTime);
    }

    private boolean spansOvernight(LocalTime openTime, LocalTime closeTime) {
        return openTime.isAfter(closeTime);
    }

    private List<MarketTradingSession> applyHoliday(List<MarketTradingSession> sessions, MarketTradingHoliday holidayRule) {
        return sessions.stream()
                .map(session -> adjustSession(session, holidayRule))
                .filter(session -> session != null && !session.openTime().equals(session.closeTime()))
                .toList();
    }

    private MarketTradingSession adjustSession(MarketTradingSession session, MarketTradingHoliday holidayRule) {
        if (!session.timezone().equals(holidayRule.timezone())) {
            return session;
        }
        if (holidayRule.holidayType() == HOLIDAY_EARLY_CLOSE && holidayRule.closeTime() != null) {
            LocalTime adjustedClose = session.closeTime().isAfter(holidayRule.closeTime())
                    ? holidayRule.closeTime()
                    : session.closeTime();
            return new MarketTradingSession(
                    session.id(),
                    session.symbol(),
                    session.dayOfWeek(),
                    session.sessionNo(),
                    session.openTime(),
                    adjustedClose,
                    session.timezone(),
                    session.enabled(),
                    session.effectiveFrom(),
                    session.effectiveTo()
            );
        }
        if (holidayRule.holidayType() == HOLIDAY_LATE_OPEN && holidayRule.openTime() != null) {
            LocalTime adjustedOpen = session.openTime().isBefore(holidayRule.openTime())
                    ? holidayRule.openTime()
                    : session.openTime();
            return new MarketTradingSession(
                    session.id(),
                    session.symbol(),
                    session.dayOfWeek(),
                    session.sessionNo(),
                    adjustedOpen,
                    session.closeTime(),
                    session.timezone(),
                    session.enabled(),
                    session.effectiveFrom(),
                    session.effectiveTo()
            );
        }
        return session;
    }
}
