package com.falconx.market.service.impl;

import com.falconx.market.entity.MarketTradingHoliday;
import com.falconx.market.entity.MarketTradingScheduleSnapshot;
import com.falconx.market.entity.MarketTradingSession;
import com.falconx.market.entity.MarketTradingSessionException;
import com.falconx.market.repository.MarketTradingHolidayRepository;
import com.falconx.market.repository.MarketTradingSessionExceptionRepository;
import com.falconx.market.repository.MarketTradingSessionRepository;
import com.falconx.market.repository.RedisMarketTradingScheduleSnapshotRepository;
import com.falconx.market.repository.mapper.MarketSymbolAdminMapper;
import com.falconx.market.repository.mapper.record.MarketSymbolQuoteMappingAdminRecord;
import com.falconx.market.service.MarketTradingScheduleWarmupService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 市场交易时间快照预热服务默认实现。
 *
 * <p>该实现会在服务启动时完成以下动作：
 *
 * <ol>
 *   <li>从 owner MySQL 读取启用的 platform_symbol、周规则、例外规则和节假日规则</li>
 *   <li>按 `platform_symbol` 聚合出完整交易时间快照</li>
 *   <li>写入 Redis，供 `trading-core-service` 下单前直接读取</li>
 * </ol>
 */
@Service
public class DefaultMarketTradingScheduleWarmupService implements MarketTradingScheduleWarmupService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMarketTradingScheduleWarmupService.class);

    private final MarketSymbolAdminMapper symbolAdminMapper;
    private final MarketTradingSessionRepository marketTradingSessionRepository;
    private final MarketTradingSessionExceptionRepository marketTradingSessionExceptionRepository;
    private final MarketTradingHolidayRepository marketTradingHolidayRepository;
    private final RedisMarketTradingScheduleSnapshotRepository marketTradingScheduleSnapshotRepository;

    public DefaultMarketTradingScheduleWarmupService(
            MarketSymbolAdminMapper symbolAdminMapper,
            MarketTradingSessionRepository marketTradingSessionRepository,
            MarketTradingSessionExceptionRepository marketTradingSessionExceptionRepository,
            MarketTradingHolidayRepository marketTradingHolidayRepository,
            RedisMarketTradingScheduleSnapshotRepository marketTradingScheduleSnapshotRepository
    ) {
        this.symbolAdminMapper = symbolAdminMapper;
        this.marketTradingSessionRepository = marketTradingSessionRepository;
        this.marketTradingSessionExceptionRepository = marketTradingSessionExceptionRepository;
        this.marketTradingHolidayRepository = marketTradingHolidayRepository;
        this.marketTradingScheduleSnapshotRepository = marketTradingScheduleSnapshotRepository;
    }

    @Override
    public void refreshAll() {
        List<MarketSymbolQuoteMappingAdminRecord> mappings = findActiveMappings();
        List<MarketTradingSession> sessions = marketTradingSessionRepository.findAllEnabled();
        List<MarketTradingSessionException> exceptions = marketTradingSessionExceptionRepository.findAll();
        List<MarketTradingHoliday> holidays = marketTradingHolidayRepository.findAll();

        // 先按 symbol / marketCode 组织规则，再一次性组装 Redis 快照。
        // 这样 trading-core 读取时只需要按 symbol 命中一份完整视图，
        // 不必在开仓链路里再做多表聚合或跨服务查库。
        Map<String, List<MarketTradingSession>> sessionsBySymbol = sessions.stream()
                .collect(Collectors.groupingBy(MarketTradingSession::symbol));
        Map<String, List<MarketTradingSessionException>> exceptionsBySymbol = exceptions.stream()
                .collect(Collectors.groupingBy(MarketTradingSessionException::symbol));
        Map<String, List<MarketTradingHoliday>> holidaysByMarketCode = holidays.stream()
                .collect(Collectors.groupingBy(MarketTradingHoliday::marketCode));

        OffsetDateTime refreshedAt = OffsetDateTime.now();
        for (MarketSymbolQuoteMappingAdminRecord mapping : mappings) {
            // 快照写入统一交给 Redis 实现类处理 TTL。
            // 这里保持“按 platform_symbol 覆盖式刷新”，避免局部增量刷新把周规则、例外规则和节假日拆散。
            marketTradingScheduleSnapshotRepository.save(new MarketTradingScheduleSnapshot(
                    mapping.platformSymbol(),
                    mapping.marketCode(),
                    sessionsBySymbol.getOrDefault(mapping.platformSymbol(), List.of()),
                    exceptionsBySymbol.getOrDefault(mapping.platformSymbol(), List.of()),
                    holidaysByMarketCode.getOrDefault(mapping.marketCode(), List.of()),
                    refreshedAt
            ));
        }
        log.info("market.trading.schedule.warmup.completed platformSymbols={} sessions={} exceptions={} holidays={}",
                mappings.size(),
                sessions.size(),
                exceptions.size(),
                holidays.size());
    }

    private List<MarketSymbolQuoteMappingAdminRecord> findActiveMappings() {
        int pageSize = 200;
        int offset = 0;
        List<MarketSymbolQuoteMappingAdminRecord> mappings = new ArrayList<>();
        while (true) {
            List<MarketSymbolQuoteMappingAdminRecord> page =
                    symbolAdminMapper.selectQuoteMappingsForList(null, null, null, 1, null, offset, pageSize);
            if (page.isEmpty()) {
                break;
            }
            mappings.addAll(page.stream()
                    .filter(mapping -> Integer.valueOf(1).equals(mapping.sourceStatus()))
                    .toList());
            if (page.size() < pageSize) {
                break;
            }
            offset += pageSize;
        }
        return mappings;
    }

    /**
     * 每日定时刷新 Redis 交易时间快照。
     *
     * <p>交易时间规则允许在运行期变更，例如节假日插入或人工例外补录。
     * 该调度用于确保 Redis 快照不会长期停留在旧规则上。
     */
    @Scheduled(
            cron = "${falconx.market.redis.trading-schedule-refresh-cron:0 0 0 * * *}",
            zone = "${falconx.market.redis.trading-schedule-refresh-zone:UTC}"
    )
    public void refreshAllOnSchedule() {
        log.info("market.trading.schedule.refresh.start reason=scheduled");
        refreshAll();
        log.info("market.trading.schedule.refresh.completed reason=scheduled");
    }
}
