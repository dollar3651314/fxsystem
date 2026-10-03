package com.falconx.market.service.impl;

import com.falconx.market.entity.MarketSymbolGroupMarkup;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.repository.MarketSymbolGroupMarkupRepository;
import com.falconx.market.service.MarketGroupMarkupService;
import java.math.BigDecimal;
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
 * 默认用户组加点服务实现。
 *
 * <p>核心约束：
 * <ul>
 *   <li>高频热路径（WS 推送、REST 出参、撮合 fillPrice）不能打 MySQL，只查内存快照</li>
 *   <li>启动时全量加载（{@link #initOnReady}），后续按固定周期增量刷新（{@link #scheduledRefresh}）</li>
 *   <li>原子切换内存快照引用，保证 lookup 无锁、无中间态</li>
 *   <li>缺行 / 禁用 / 全 0 加点统一返回原 quote，零分配回退</li>
 * </ul>
 */
@Service
public class DefaultMarketGroupMarkupService implements MarketGroupMarkupService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMarketGroupMarkupService.class);
    private static final String DEFAULT_GROUP_CODE = "default";

    private final MarketSymbolGroupMarkupRepository repository;

    private volatile Map<GroupSymbolKey, MarketSymbolGroupMarkup> snapshot = Map.of();
    private final AtomicReference<OffsetDateTime> lastRefreshAt = new AtomicReference<>(OffsetDateTime.MIN);
    private volatile boolean initialized = false;

    public DefaultMarketGroupMarkupService(MarketSymbolGroupMarkupRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initOnReady() {
        try {
            refresh();
            log.info("market.group-markup.init.ready snapshotSize={}", snapshot.size());
        } catch (RuntimeException error) {
            log.error("market.group-markup.init.failed reason={}", error.toString(), error);
            throw error;
        }
    }

    @Scheduled(fixedDelayString = "${falconx.market.group-markup.refresh-interval-ms:30000}")
    public void scheduledRefresh() {
        if (!initialized) {
            return;
        }
        try {
            int applied = refreshIncremental();
            if (applied > 0) {
                log.info("market.group-markup.refresh.incremental applied={} snapshotSize={}",
                        applied, snapshot.size());
            }
        } catch (RuntimeException error) {
            log.warn("market.group-markup.refresh.failed reason={}", error.toString());
        }
    }

    @Override
    public synchronized void refresh() {
        List<MarketSymbolGroupMarkup> all = repository.findAllEnabled();
        Map<GroupSymbolKey, MarketSymbolGroupMarkup> next = new HashMap<>(all.size() * 2);
        OffsetDateTime maxUpdatedAt = OffsetDateTime.MIN;
        for (MarketSymbolGroupMarkup item : all) {
            next.put(key(item.groupCode(), item.platformSymbol()), item);
            if (item.updatedAt() != null && item.updatedAt().isAfter(maxUpdatedAt)) {
                maxUpdatedAt = item.updatedAt();
            }
        }
        snapshot = Map.copyOf(next);
        lastRefreshAt.set(maxUpdatedAt.equals(OffsetDateTime.MIN) ? OffsetDateTime.now() : maxUpdatedAt);
        initialized = true;
        log.info("market.group-markup.refresh.full size={} lastRefreshAt={}", snapshot.size(), lastRefreshAt.get());
    }

    private synchronized int refreshIncremental() {
        OffsetDateTime since = lastRefreshAt.get();
        List<MarketSymbolGroupMarkup> changes = repository.findChangedSince(since);
        if (changes.isEmpty()) {
            return 0;
        }
        Map<GroupSymbolKey, MarketSymbolGroupMarkup> next = new HashMap<>(snapshot);
        OffsetDateTime maxUpdatedAt = since;
        for (MarketSymbolGroupMarkup item : changes) {
            GroupSymbolKey k = key(item.groupCode(), item.platformSymbol());
            if (item.enabled() == 1) {
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
    public Optional<MarketSymbolGroupMarkup> find(String groupCode, String platformSymbol) {
        if (platformSymbol == null || platformSymbol.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.get(key(groupCode, platformSymbol)));
    }

    @Override
    public List<MarketSymbolGroupMarkup> findAllEnabled() {
        return List.copyOf(snapshot.values());
    }

    @Override
    public List<MarketSymbolGroupMarkup> findChangedSince(OffsetDateTime since) {
        return repository.findChangedSince(since);
    }

    @Override
    public StandardQuote applyMarkup(StandardQuote quote, String groupCode) {
        if (quote == null) {
            return null;
        }
        Optional<MarketSymbolGroupMarkup> hit = find(groupCode, quote.symbol());
        if (hit.isEmpty()) {
            return quote;
        }
        MarketSymbolGroupMarkup markup = hit.get();
        if (markup.enabled() != 1) {
            return quote;
        }
        BigDecimal bidExtra = markup.bidExtra();
        BigDecimal askExtra = markup.askExtra();
        if ((bidExtra == null || bidExtra.signum() == 0) && (askExtra == null || askExtra.signum() == 0)) {
            return quote;
        }
        return quote.withExtraMarkup(bidExtra, askExtra);
    }

    private GroupSymbolKey key(String groupCode, String platformSymbol) {
        String g = (groupCode == null || groupCode.isBlank()) ? DEFAULT_GROUP_CODE : groupCode.trim();
        String s = platformSymbol.trim().toUpperCase(Locale.ROOT);
        return new GroupSymbolKey(g, s);
    }

    private record GroupSymbolKey(String groupCode, String platformSymbol) {
    }
}
