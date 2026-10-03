package com.falconx.trading.service.impl;

import com.falconx.trading.repository.TradingScheduleSnapshotRepository;
import com.falconx.trading.service.TradingScheduleService;
import com.falconx.trading.service.model.TradingHolidayRule;
import com.falconx.trading.service.model.TradingHoursExceptionRule;
import com.falconx.trading.service.model.TradingScheduleSnapshot;
import com.falconx.trading.service.model.TradingSessionWindow;
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
 * 交易时间校验服务默认实现。
 *
 * <p>该实现固定遵循方案 B 的优先级：
 *
 * <ol>
 *   <li>`t_trading_hours_exception`</li>
 *   <li>`t_trading_holiday`</li>
 *   <li>`t_trading_hours`</li>
 * </ol>
 *
 * <p>校验数据只允许来自 Redis 交易时间快照，不允许跨服务查库。
 */
@Service
public class DefaultTradingScheduleService implements TradingScheduleService {

    private static final Logger log = LoggerFactory.getLogger(DefaultTradingScheduleService.class);

    // 例外规则类型（对应 TradingHoursExceptionRule.exceptionType）
    private static final int EXCEPTION_FULL_CLOSE      = 1;
    private static final int EXCEPTION_SPECIAL_SESSION = 2;

    // 节假日规则类型（对应 TradingHolidayRule.holidayType）
    private static final int HOLIDAY_FULL_CLOSE  = 1;
    private static final int HOLIDAY_EARLY_CLOSE = 2;
    private static final int HOLIDAY_LATE_OPEN   = 3;

    private final TradingScheduleSnapshotRepository tradingScheduleSnapshotRepository;

    public DefaultTradingScheduleService(TradingScheduleSnapshotRepository tradingScheduleSnapshotRepository) {
        this.tradingScheduleSnapshotRepository = tradingScheduleSnapshotRepository;
    }

    @Override
    public boolean isOpenAllowed(String symbol, OffsetDateTime now) {
        TradingScheduleSnapshot snapshot = tradingScheduleSnapshotRepository.findBySymbol(symbol).orElse(null);
        // 交易时间校验的 owner 是 market-service 写入的 Redis 快照。
        // 如果这里 cache miss，就按当前全局规则直接拒绝开仓，而不是回退成跨服务查库或“默认放行”。
        if (snapshot == null) {
            log.warn("trading.schedule.snapshot.missing symbol={} action=reject", symbol);
            return false;
        }

        // 例外规则优先级最高，设计目的就是覆盖周规则和节假日规则。
        // 因此只要命中 FULL_CLOSE，后续不再继续看 holiday / weekly。
        List<TradingHoursExceptionRule> matchingExceptions = snapshot.exceptions().stream()
                .filter(rule -> matchesExceptionDate(now, rule))
                .sorted(Comparator.comparing(TradingHoursExceptionRule::sessionNo,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (matchingExceptions.stream().anyMatch(rule -> rule.exceptionType() == EXCEPTION_FULL_CLOSE)) {
            log.info("trading.schedule.blocked symbol={} reason=exception-full-close", symbol);
            return false;
        }
        List<TradingHoursExceptionRule> specialSessions = matchingExceptions.stream()
                .filter(rule -> rule.exceptionType() == EXCEPTION_SPECIAL_SESSION)
                .toList();
        if (!specialSessions.isEmpty()) {
            // 命中特殊时段时，业务语义是“今天只按人工覆盖的 session 判断”，
            // 不能再把基础周规则拼回去，否则人工停盘或特殊开盘时段会失效。
            boolean tradable = specialSessions.stream().anyMatch(rule -> isExceptionSessionActive(now, rule));
            // 2026-05-20 perf: 每 tick 触发评估，从 INFO 降到 DEBUG（异常时段命中虽不算高频，但也不应每秒打）
            log.debug("trading.schedule.evaluated symbol={} source=exception tradable={}", symbol, tradable);
            return tradable;
        }

        List<TradingSessionWindow> baseSessions = snapshot.sessions().stream()
                .filter(TradingSessionWindow::enabled)
                .sorted(Comparator.comparingInt(TradingSessionWindow::sessionNo))
                .toList();

        TradingHolidayRule holidayRule = snapshot.holidays().stream()
                .filter(rule -> matchesDate(now, rule.holidayDate(), rule.timezone()))
                .findFirst()
                .orElse(null);
        if (holidayRule != null) {
            // 节假日规则面向 market_code，作用是对基础周规则做“整日休市/提前收盘/晚开盘”的覆盖。
            // 这里不会创建新 session，只是在原 session 上做边界裁剪，避免和 exception 语义重叠。
            if (holidayRule.holidayType() == HOLIDAY_FULL_CLOSE) {
                log.info("trading.schedule.blocked symbol={} reason=holiday-full-close marketCode={}",
                        symbol,
                        snapshot.marketCode());
                return false;
            }
            baseSessions = applyHoliday(baseSessions, holidayRule);
        }

        // 只有在没有 exception、没有 full-close holiday 的情况下，才回退到常规周规则判断。
        // 这也是方案 B 的最终兜底路径。
        boolean tradable = baseSessions.stream().anyMatch(rule -> isSessionActive(now, rule));
        // 2026-05-20 perf: 每 tick 触发评估的高频路径，从 INFO 降到 DEBUG，节流日志写入
        log.debug("trading.schedule.evaluated symbol={} source=weekly tradable={} sessions={}",
                symbol,
                tradable,
                baseSessions.size());
        return tradable;
    }

    @Override
    public boolean isCloseAllowed(String symbol, OffsetDateTime now) {
        boolean allowed = isOpenAllowed(symbol, now);
        // 2026-05-20 perf: 评估型日志降 DEBUG；真正发生 close 操作的日志由调用方在 INFO 打
        log.debug("trading.schedule.close.evaluated symbol={} allowed={}", symbol, allowed);
        return allowed;
    }

    private boolean matchesDate(OffsetDateTime now, LocalDate tradeDate, String timezone) {
        return now.atZoneSameInstant(ZoneId.of(timezone)).toLocalDate().equals(tradeDate);
    }

    private boolean matchesExceptionDate(OffsetDateTime now, TradingHoursExceptionRule rule) {
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

    private boolean isExceptionSessionActive(OffsetDateTime now, TradingHoursExceptionRule rule) {
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

    private boolean isSessionActive(OffsetDateTime now, TradingSessionWindow rule) {
        ZonedDateTime zonedNow = now.atZoneSameInstant(ZoneId.of(rule.timezone()));
        LocalDate anchorDate = resolveAnchorDate(zonedNow, rule);
        if (anchorDate == null) {
            return false;
        }
        return withinEffectiveRange(anchorDate, rule.effectiveFrom(), rule.effectiveTo())
                && withinWindow(zonedNow.toLocalTime(), rule.openTime(), rule.closeTime());
    }

    private LocalDate resolveAnchorDate(ZonedDateTime zonedNow, TradingSessionWindow rule) {
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

    private List<TradingSessionWindow> applyHoliday(List<TradingSessionWindow> sessions, TradingHolidayRule holidayRule) {
        return sessions.stream()
                .map(session -> adjustSession(session, holidayRule))
                .filter(session -> session != null && !session.openTime().equals(session.closeTime()))
                .toList();
    }

    private TradingSessionWindow adjustSession(TradingSessionWindow session, TradingHolidayRule holidayRule) {
        if (!session.timezone().equals(holidayRule.timezone())) {
            return session;
        }
        if (holidayRule.holidayType() == HOLIDAY_EARLY_CLOSE && holidayRule.closeTime() != null) {
            LocalTime adjustedClose = session.closeTime().isAfter(holidayRule.closeTime())
                    ? holidayRule.closeTime()
                    : session.closeTime();
            return new TradingSessionWindow(
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
            return new TradingSessionWindow(
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
