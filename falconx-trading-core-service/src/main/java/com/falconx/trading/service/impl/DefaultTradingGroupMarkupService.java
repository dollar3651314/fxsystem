package com.falconx.trading.service.impl;

import com.falconx.market.contract.event.MarketGroupMarkupItem;
import com.falconx.market.contract.event.MarketGroupMarkupListResponse;
import com.falconx.trading.client.MarketGroupMarkupClient;
import com.falconx.trading.service.TradingGroupMarkupService;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * STAGE-12-GROUP-MARKUP：trading-core 本地组加点服务的默认实现。
 *
 * <p>设计意图：
 * <ul>
 *   <li>启动时通过 {@link MarketGroupMarkupClient} 全量加载到内存快照，加载失败 fail-fast</li>
 *   <li>30s 定时调用 changes-since 增量刷新；失败不影响主链路</li>
 *   <li>find lookup 完全本地，零 IO，零锁（volatile + 不可变 Map）</li>
 *   <li>cache miss 与 enabled=0 视为 "无加点"，返回 Optional.empty()</li>
 * </ul>
 */
@Service
public class DefaultTradingGroupMarkupService implements TradingGroupMarkupService {

    private static final Logger log = LoggerFactory.getLogger(DefaultTradingGroupMarkupService.class);
    private static final String DEFAULT_GROUP_CODE = "default";

    private final MarketGroupMarkupClient client;

    private volatile Map<GroupSymbolKey, MarketGroupMarkupItem> snapshot = Map.of();
    private final AtomicReference<OffsetDateTime> lastRefreshAt = new AtomicReference<>(OffsetDateTime.MIN);
    private volatile boolean initialized = false;

    public DefaultTradingGroupMarkupService(MarketGroupMarkupClient client) {
        this.client = client;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initOnReady() {
        try {
            refresh();
            log.info("trading.group-markup.init.ready snapshotSize={}", snapshot.size());
        } catch (RuntimeException error) {
            // 2026-05-27 P0 加固：init 失败不再 fail-fast 拖垮整个服务启动。
            // 触发场景：并行部署时 gateway 尚未就绪，group-markup init 调 gateway Connection refused
            //          → 原 throw 让 ApplicationReadyEvent listener 抛异常 → Spring Application run failed
            //          → trading-core 进程退出 → 全部 REST 504 + consumer 停止。
            // 降级策略：snapshot 保持空（无 group 加点，下单用原始 LP 价，安全），
            //          initialized=false，scheduledRefresh 每 30s 自动重试 refresh()，gateway 就绪后补齐。
            log.error("trading.group-markup.init.failed reason={} action=degrade-and-retry-by-scheduler",
                    error.toString(), error);
        }
    }

    @Scheduled(fixedDelayString = "${falconx.trading.group-markup.refresh-interval-ms:30000}")
    public void scheduledRefresh() {
        try {
            if (!initialized) {
                refresh();
                return;
            }
            int applied = refreshIncremental();
            if (applied > 0) {
                log.info("trading.group-markup.refresh.incremental applied={} snapshotSize={}",
                        applied, snapshot.size());
            }
        } catch (RuntimeException error) {
            log.warn("trading.group-markup.refresh.failed reason={}", error.toString());
        }
    }

    @Override
    public synchronized void refresh() {
        MarketGroupMarkupListResponse response = client.fetchAllEnabled();
        List<MarketGroupMarkupItem> all = response.items() == null ? List.of() : response.items();
        Map<GroupSymbolKey, MarketGroupMarkupItem> next = new HashMap<>(all.size() * 2);
        OffsetDateTime maxUpdatedAt = OffsetDateTime.MIN;
        for (MarketGroupMarkupItem item : all) {
            if (!item.enabled()) {
                continue;
            }
            next.put(key(item.groupCode(), item.platformSymbol()), item);
            if (item.updatedAt() != null && item.updatedAt().isAfter(maxUpdatedAt)) {
                maxUpdatedAt = item.updatedAt();
            }
        }
        snapshot = Map.copyOf(next);
        OffsetDateTime serverTime = response.serverTime() == null ? OffsetDateTime.now() : response.serverTime();
        lastRefreshAt.set(maxUpdatedAt.equals(OffsetDateTime.MIN) ? serverTime : maxUpdatedAt);
        initialized = true;
        log.info("trading.group-markup.refresh.full size={} lastRefreshAt={}", snapshot.size(), lastRefreshAt.get());
    }

    private synchronized int refreshIncremental() {
        OffsetDateTime since = lastRefreshAt.get();
        MarketGroupMarkupListResponse response = client.fetchChangesSince(since);
        List<MarketGroupMarkupItem> changes = response.items() == null ? List.of() : response.items();
        if (changes.isEmpty()) {
            return 0;
        }
        Map<GroupSymbolKey, MarketGroupMarkupItem> next = new HashMap<>(snapshot);
        OffsetDateTime maxUpdatedAt = since;
        for (MarketGroupMarkupItem item : changes) {
            GroupSymbolKey k = key(item.groupCode(), item.platformSymbol());
            if (item.enabled()) {
                next.put(k, item);
            } else {
                next.remove(k);
            }
            if (item.updatedAt() != null && item.updatedAt().isAfter(maxUpdatedAt)) {
                maxUpdatedAt = item.updatedAt();
            }
        }
        snapshot = Map.copyOf(next);
        lastRefreshAt.set(maxUpdatedAt);
        return changes.size();
    }

    @Override
    public Optional<MarketGroupMarkupItem> find(String groupCode, String platformSymbol) {
        if (platformSymbol == null || platformSymbol.isBlank()) {
            return Optional.empty();
        }
        MarketGroupMarkupItem hit = snapshot.get(key(groupCode, platformSymbol));
        if (hit == null || !hit.enabled()) {
            return Optional.empty();
        }
        return Optional.of(hit);
    }

    private GroupSymbolKey key(String groupCode, String platformSymbol) {
        String g = (groupCode == null || groupCode.isBlank()) ? DEFAULT_GROUP_CODE : groupCode.trim();
        String s = platformSymbol.trim().toUpperCase(Locale.ROOT);
        return new GroupSymbolKey(g, s);
    }

    private record GroupSymbolKey(String groupCode, String platformSymbol) {
    }
}
