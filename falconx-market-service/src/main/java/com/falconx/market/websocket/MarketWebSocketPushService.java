package com.falconx.market.websocket;

import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.KlineSnapshot;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.repository.MarketLatestQuoteRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * market-service WebSocket 推送编排服务。
 *
 * <p>该服务把报价、K 线和 stale 通知统一收口到 session registry，
 * 避免上游 ingestion 和下游连接管理互相耦合。
 */
@Service
public class MarketWebSocketPushService {

    private final MarketWebSocketSessionRegistry sessionRegistry;
    private final MarketLatestQuoteRepository marketLatestQuoteRepository;
    private final MarketServiceProperties properties;

    public MarketWebSocketPushService(MarketWebSocketSessionRegistry sessionRegistry,
                                      MarketLatestQuoteRepository marketLatestQuoteRepository,
                                      MarketServiceProperties properties) {
        this.sessionRegistry = sessionRegistry;
        this.marketLatestQuoteRepository = marketLatestQuoteRepository;
        this.properties = properties;
    }

    public void publishQuote(StandardQuote quote) {
        sessionRegistry.publishQuote(quote);
    }

    public void publishKline(KlineSnapshot snapshot) {
        sessionRegistry.publishKline(snapshot);
    }

    /**
     * 2026-05-26 性能加固（Sprint 1 Phase B2 / 性能分析报告 §4 P0）：
     * 把"每秒 N 次单 findBySymbol"改为"每秒 1 次 findBySymbols 批量 pipeline"。
     *
     * <p>原实现：N=100 个 subscribed symbols → 100 次 Redis HGETALL（RTT × 100）。
     * 现实现：1 次 pipeline HGETALL，~1 RTT。在 1571 symbol 场景理论降到 100ms 以内。
     * 行为不变：只 publishStale 真实 stale 的 symbol。
     */
    @Scheduled(fixedDelayString = "${falconx.market.web-socket.stale-scan-interval:1s}")
    public void scanAndPublishStaleQuotes() {
        Set<String> symbols = sessionRegistry.subscribedSymbols();
        if (symbols.isEmpty()) {
            return;
        }
        Map<String, StandardQuote> snapshot = marketLatestQuoteRepository.findBySymbols(new ArrayList<>(symbols));
        for (StandardQuote quote : snapshot.values()) {
            if (quote.stale()) {
                sessionRegistry.publishStale(quote);
            }
        }
    }
}
