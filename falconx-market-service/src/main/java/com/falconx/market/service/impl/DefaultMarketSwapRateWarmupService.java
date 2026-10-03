package com.falconx.market.service.impl;

import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.MarketSwapRate;
import com.falconx.market.entity.MarketSwapRateRule;
import com.falconx.market.entity.MarketSwapRateSnapshot;
import com.falconx.market.repository.MarketSwapRateRepository;
import com.falconx.market.repository.RedisMarketSwapRateSnapshotRepository;
import com.falconx.market.repository.mapper.MarketSymbolAdminMapper;
import com.falconx.market.repository.mapper.record.MarketSymbolQuoteMappingAdminRecord;
import com.falconx.market.service.MarketSwapRateWarmupService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 隔夜利息费率共享快照预热服务默认实现。
 *
 * <p>该实现会在服务启动时从 owner MySQL 读取所有 `t_swap_rate` 规则，
 * 按 `platform_symbol` 聚合为完整历史快照后写入 Redis，供 `trading-core-service`
 * 在结算时按结算日解析有效费率。
 */
@Service
public class DefaultMarketSwapRateWarmupService implements MarketSwapRateWarmupService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMarketSwapRateWarmupService.class);

    private final MarketSymbolAdminMapper symbolAdminMapper;
    private final MarketSwapRateRepository marketSwapRateRepository;
    private final RedisMarketSwapRateSnapshotRepository marketSwapRateSnapshotRepository;
    private final MarketServiceProperties properties;

    public DefaultMarketSwapRateWarmupService(MarketSymbolAdminMapper symbolAdminMapper,
                                              MarketSwapRateRepository marketSwapRateRepository,
                                              RedisMarketSwapRateSnapshotRepository marketSwapRateSnapshotRepository,
                                              MarketServiceProperties properties) {
        this.symbolAdminMapper = symbolAdminMapper;
        this.marketSwapRateRepository = marketSwapRateRepository;
        this.marketSwapRateSnapshotRepository = marketSwapRateSnapshotRepository;
        this.properties = properties;
    }

    @Override
    public void refreshAll() {
        List<MarketSymbolQuoteMappingAdminRecord> mappings = findActiveMappings();
        List<MarketSwapRate> rates = marketSwapRateRepository.findAllOrdered();
        Map<String, List<MarketSwapRateRule>> ratesBySymbol = rates.stream()
                .collect(Collectors.groupingBy(
                        MarketSwapRate::symbol,
                        Collectors.mapping(
                                rate -> new MarketSwapRateRule(
                                        rate.effectiveFrom(),
                                        rate.rolloverTime(),
                                        rate.longRate(),
                                        rate.shortRate()
                                ),
                                Collectors.collectingAndThen(Collectors.toList(), list -> list.stream()
                                        .sorted(Comparator.comparing(MarketSwapRateRule::effectiveFrom))
                                        .toList())
                        )
                ));

        OffsetDateTime refreshedAt = OffsetDateTime.now();
        for (MarketSymbolQuoteMappingAdminRecord mapping : mappings) {
            marketSwapRateSnapshotRepository.save(new MarketSwapRateSnapshot(
                    mapping.platformSymbol(),
                    ratesBySymbol.getOrDefault(mapping.platformSymbol(), List.of()),
                    refreshedAt
            ));
        }
        log.info("market.swap-rate.warmup.completed platformSymbols={} rules={}",
                mappings.size(),
                rates.size());
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
     * 每日定时刷新 Redis 隔夜利息快照。
     *
     * <p>费率 owner 数据允许在运行期预先录入后续生效日，
     * 该调度用于确保 `trading-core-service` 读取到的共享快照持续跟随 owner 数据更新。
     */
    @Scheduled(
            cron = "${falconx.market.redis.swap-rate-refresh-cron:0 0 0 * * *}",
            zone = "${falconx.market.redis.swap-rate-refresh-zone:UTC}"
    )
    public void refreshAllOnSchedule() {
        log.info("market.swap-rate.refresh.start reason=scheduled cron={} zone={}",
                properties.getRedis().getSwapRateRefreshCron(),
                properties.getRedis().getSwapRateRefreshZone());
        refreshAll();
        log.info("market.swap-rate.refresh.completed reason=scheduled");
    }
}
