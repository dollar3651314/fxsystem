package com.falconx.market.websocket;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.falconx.common.error.CommonErrorCode;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.infrastructure.trace.TraceIdSupport;
import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.KlineSnapshot;
import com.falconx.market.entity.MarketSymbolWithSpec;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.repository.MarketLatestQuoteRepository;
import com.falconx.market.repository.MarketReferenceQuoteRepository;
import com.falconx.market.repository.MarketSymbolRepository;
import com.falconx.market.service.MarketGroupMarkupService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * market-service WebSocket session registry。
 *
 * <p>该组件统一负责：
 *
 * <ul>
 *   <li>连接注册与关闭</li>
 *   <li>订阅协议解析</li>
 *   <li>行情 / K 线 / stale 推送</li>
 *   <li>Ping / Pong 心跳超时</li>
 * </ul>
 */
@Component
public class MarketWebSocketSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(MarketWebSocketSessionRegistry.class);
    private static final String PRICE_TICK_CHANNEL = "price.tick";
    private static final String KLINE_PREFIX = "kline.";
    private static final String ERROR_TYPE = "error";
    private static final String SUBSCRIBED_TYPE = "subscribed";
    private static final String UNSUBSCRIBED_TYPE = "unsubscribed";
    private static final String PONG_TYPE = "pong";

    private final ObjectMapper objectMapper;
    private final MarketSymbolRepository marketSymbolRepository;
    private final MarketLatestQuoteRepository marketLatestQuoteRepository;
    private final MarketReferenceQuoteRepository marketReferenceQuoteRepository;
    private final MarketGroupMarkupService marketGroupMarkupService;
    private final Set<String> supportedKlineChannels;
    private final Duration pingInterval;
    private final Duration pongTimeout;
    private final ScheduledExecutorService heartbeatExecutor;
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<ChannelSymbolKey, Set<String>> channelSymbolSessionIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> staleNotifiedAtBySymbol = new ConcurrentHashMap<>();

    @Autowired
    public MarketWebSocketSessionRegistry(ObjectMapper objectMapper,
                                          MarketSymbolRepository marketSymbolRepository,
                                          MarketLatestQuoteRepository marketLatestQuoteRepository,
                                          MarketReferenceQuoteRepository marketReferenceQuoteRepository,
                                          MarketGroupMarkupService marketGroupMarkupService,
                                          MarketServiceProperties properties,
                                          ObjectProvider<MeterRegistry> meterRegistry) {
        this(objectMapper,
                marketSymbolRepository,
                marketLatestQuoteRepository,
                marketReferenceQuoteRepository,
                marketGroupMarkupService,
                properties,
                meterRegistry.getIfAvailable());
    }

    MarketWebSocketSessionRegistry(ObjectMapper objectMapper,
                                   MarketSymbolRepository marketSymbolRepository,
                                   MarketLatestQuoteRepository marketLatestQuoteRepository,
                                   MarketReferenceQuoteRepository marketReferenceQuoteRepository,
                                   MarketGroupMarkupService marketGroupMarkupService,
                                   MarketServiceProperties properties) {
        this(objectMapper,
                marketSymbolRepository,
                marketLatestQuoteRepository,
                marketReferenceQuoteRepository,
                marketGroupMarkupService,
                properties,
                (MeterRegistry) null);
    }

    MarketWebSocketSessionRegistry(ObjectMapper objectMapper,
                                   MarketSymbolRepository marketSymbolRepository,
                                   MarketLatestQuoteRepository marketLatestQuoteRepository,
                                   MarketReferenceQuoteRepository marketReferenceQuoteRepository,
                                   MarketGroupMarkupService marketGroupMarkupService,
                                   MarketServiceProperties properties,
                                   MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.marketSymbolRepository = marketSymbolRepository;
        this.marketLatestQuoteRepository = marketLatestQuoteRepository;
        this.marketReferenceQuoteRepository = marketReferenceQuoteRepository;
        this.marketGroupMarkupService = marketGroupMarkupService;
        this.supportedKlineChannels = properties.getKline().getIntervals().stream()
                .map(interval -> KLINE_PREFIX + interval.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        this.pingInterval = properties.getWebSocket().getPingInterval();
        this.pongTimeout = properties.getWebSocket().getPongTimeout();
        this.heartbeatExecutor = Executors.newScheduledThreadPool(2, new WebSocketHeartbeatThreadFactory());
        registerMetrics(meterRegistry);
    }

    public void register(WebSocketSession rawSession) {
        String traceId = TraceIdSupport.reuseOrCreate(attribute(rawSession, MarketWebSocketHandshakeInterceptor.ATTRIBUTE_TRACE_ID));
        ConcurrentWebSocketSessionDecorator session = new ConcurrentWebSocketSessionDecorator(rawSession, 10_000, 512 * 1024);
        SessionState state = new SessionState(
                session,
                traceId,
                attribute(rawSession, MarketWebSocketHandshakeInterceptor.ATTRIBUTE_USER_ID),
                attribute(rawSession, MarketWebSocketHandshakeInterceptor.ATTRIBUTE_UID),
                attribute(rawSession, MarketWebSocketHandshakeInterceptor.ATTRIBUTE_STATUS),
                normalizeGroupCode(attribute(rawSession, MarketWebSocketHandshakeInterceptor.ATTRIBUTE_GROUP_CODE))
        );
        sessions.put(session.getId(), state);
        state.pingFuture = heartbeatExecutor.scheduleAtFixedRate(
                () -> sendHeartbeat(state),
                pingInterval.toMillis(),
                pingInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
        state.watchdogFuture = heartbeatExecutor.scheduleAtFixedRate(
                () -> checkHeartbeat(state),
                1_000L,
                1_000L,
                TimeUnit.MILLISECONDS
        );
        withTrace(state.traceId(), () -> log.info("market.websocket.session.opened sessionId={} userId={} uid={} status={} groupCode={} activeSessions={}",
                session.getId(),
                state.userId(),
                state.uid(),
                state.status(),
                state.groupCode(),
                sessions.size()));
    }

    public void unregister(String sessionId, CloseStatus closeStatus, String reason) {
        SessionState state = sessions.remove(sessionId);
        if (state == null) {
            return;
        }
        removeIndexes(state, state.allSubscriptions());
        cancel(state.pingFuture);
        cancel(state.watchdogFuture);
        closeQuietly(state.session(), closeStatus);
        withTrace(state.traceId(), () -> log.info("market.websocket.session.closed sessionId={} userId={} closeCode={} reason={} activeSessions={}",
                sessionId,
                state.userId(),
                closeStatus.getCode(),
                reason,
                sessions.size()));
    }

    public void handleTextMessage(String sessionId, String payload) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return;
        }
        withTrace(state.traceId(), () -> {
            try {
                JsonNode root = objectMapper.readTree(payload);
                String type = root.path("type").asText("");
                switch (type) {
                    case "subscribe" -> handleSubscribe(state, root);
                    case "unsubscribe" -> handleUnsubscribe(state, root);
                    case "ping" -> sendPong(state, root.path("ts").asText(""));
                    default -> sendError(state, root.path("requestId").asText(null),
                            CommonErrorCode.INVALID_REQUEST_PAYLOAD.code(),
                            CommonErrorCode.INVALID_REQUEST_PAYLOAD.message());
                }
            } catch (tools.jackson.core.JacksonException exception) {
                sendError(state, null,
                        CommonErrorCode.INVALID_REQUEST_PAYLOAD.code(),
                        CommonErrorCode.INVALID_REQUEST_PAYLOAD.message());
            }
        });
    }

    public void markPong(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return;
        }
        state.lastPongAtMillis().set(System.currentTimeMillis());
        withTrace(state.traceId(), () -> log.debug("market.websocket.session.pong sessionId={} userId={}",
                sessionId,
                state.userId()));
    }

    public void publishQuote(StandardQuote quote) {
        staleNotifiedAtBySymbol.remove(quote.symbol());
        int recipients = 0;
        Set<String> sessionIds = channelSymbolSessionIds.get(new ChannelSymbolKey(PRICE_TICK_CHANNEL, quote.symbol()));
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }
        for (String sessionId : sessionIds) {
            SessionState state = sessions.get(sessionId);
            if (state == null || !state.isSubscribed(PRICE_TICK_CHANNEL, quote.symbol())) {
                sessionIds.remove(sessionId);
                continue;
            }
            recipients++;
            sendPriceTick(state, quote);
        }
        if (recipients > 0) {
            // STAGE-12 perf：单 symbol 行情活跃时 21 push/s，多 symbol 可达 260 行/秒。
            // 降级到 DEBUG —— 故障排查需要时打开 DEBUG，正常运行不输出每条 push。
            log.debug("market.websocket.price.push symbol={} stale={} recipientCount={} quoteTs={} source={}",
                    quote.symbol(),
                    quote.stale(),
                    recipients,
                    quote.ts(),
                    quote.source());
        }
    }

    public void publishKline(KlineSnapshot snapshot) {
        String channel = KLINE_PREFIX + snapshot.interval().toLowerCase(Locale.ROOT);
        int recipients = 0;
        Set<String> sessionIds = channelSymbolSessionIds.get(new ChannelSymbolKey(channel, snapshot.symbol()));
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }
        for (String sessionId : sessionIds) {
            SessionState state = sessions.get(sessionId);
            if (state == null || !state.isSubscribed(channel, snapshot.symbol())) {
                sessionIds.remove(sessionId);
                continue;
            }
            recipients++;
            sendJson(state, new KlineFrame(
                    channel,
                    snapshot.symbol(),
                    snapshot.interval(),
                    snapshot.open().toPlainString(),
                    snapshot.high().toPlainString(),
                    snapshot.low().toPlainString(),
                    snapshot.close().toPlainString(),
                    snapshot.volume().toPlainString(),
                    snapshot.openTime().toString(),
                    snapshot.closeTime().toString(),
                    snapshot.isFinal()
            ));
        }
        if (recipients > 0) {
            // STAGE-12 perf：每秒可能多条 kline push（active candle 实时刷新），降 DEBUG。
            log.debug("market.websocket.kline.push symbol={} interval={} isFinal={} recipientCount={} closeTime={}",
                    snapshot.symbol(),
                    snapshot.interval(),
                    snapshot.isFinal(),
                    recipients,
                    snapshot.closeTime());
        }
    }

    public void publishStale(StandardQuote quote) {
        long quoteTsMillis = quote.ts().toInstant().toEpochMilli();
        Long previous = staleNotifiedAtBySymbol.putIfAbsent(quote.symbol(), quoteTsMillis);
        if (previous != null && previous >= quoteTsMillis) {
            return;
        }
        int recipients = 0;
        Set<String> sessionIds = channelSymbolSessionIds.get(new ChannelSymbolKey(PRICE_TICK_CHANNEL, quote.symbol()));
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }
        for (String sessionId : sessionIds) {
            SessionState state = sessions.get(sessionId);
            if (state == null || !state.isSubscribed(PRICE_TICK_CHANNEL, quote.symbol())) {
                sessionIds.remove(sessionId);
                continue;
            }
            recipients++;
            sendJson(state, new StalePriceFrame(
                    PRICE_TICK_CHANNEL,
                    quote.symbol(),
                    true,
                    quote.qualityStatus() == null ? "STALE" : quote.qualityStatus().name(),
                    quote.qualityReason() == null ? "QUOTE_TIME_DRIFT_EXCEEDED" : quote.qualityReason(),
                    quote.ts().toString()
            ));
        }
        if (recipients > 0) {
            log.warn("market.websocket.price.stale-push symbol={} recipientCount={} quoteTs={}",
                    quote.symbol(),
                    recipients,
                    quote.ts());
        }
    }

    public Set<String> subscribedSymbols() {
        return channelSymbolSessionIds.keySet().stream()
                .map(ChannelSymbolKey::symbol)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    private void handleSubscribe(SessionState state, JsonNode root) {
        String requestId = root.path("requestId").asText(null);
        List<String> channels = readStringArray(root.get("channels"));
        if (channels.isEmpty()) {
            sendError(state, requestId,
                    CommonErrorCode.INVALID_REQUEST_PAYLOAD.code(),
                    CommonErrorCode.INVALID_REQUEST_PAYLOAD.message());
            return;
        }
        List<String> normalizedChannels = normalizeChannels(channels);
        if (normalizedChannels.isEmpty()) {
            sendError(state, requestId,
                    CommonErrorCode.INVALID_REQUEST_PAYLOAD.code(),
                    CommonErrorCode.INVALID_REQUEST_PAYLOAD.message());
            return;
        }

        List<String> requestedSymbols = readStringArray(root.get("symbols"));
        List<String> resolvedSymbols = resolveSymbols(requestedSymbols, state.groupCode());
        if (resolvedSymbols == null) {
            String invalidSymbol = firstUnknownSymbol(requestedSymbols, state.groupCode()).orElse("UNKNOWN");
            sendError(state, requestId, "30001", "symbol not found: " + invalidSymbol);
            return;
        }

        state.subscribe(normalizedChannels, resolvedSymbols);
        addIndexes(state, normalizedChannels, resolvedSymbols);
        sendJson(state, new SubscriptionFrame(SUBSCRIBED_TYPE, requestId, normalizedChannels, resolvedSymbols));
        publishCurrentPriceSnapshots(state, normalizedChannels, resolvedSymbols);
        log.info("market.websocket.subscribe.accepted sessionId={} userId={} groupCode={} requestId={} channels={} symbols={} channelCount={} symbolCount={}",
                state.session().getId(),
                state.userId(),
                state.groupCode(),
                requestId,
                normalizedChannels,
                resolvedSymbols,
                normalizedChannels.size(),
                resolvedSymbols.size());
    }

    private void handleUnsubscribe(SessionState state, JsonNode root) {
        String requestId = root.path("requestId").asText(null);
        List<String> channels = normalizeChannels(readStringArray(root.get("channels")));
        List<String> symbols = resolveSymbols(readStringArray(root.get("symbols")), state.groupCode());
        if (channels.isEmpty() || symbols == null) {
            sendError(state, requestId,
                    CommonErrorCode.INVALID_REQUEST_PAYLOAD.code(),
                    CommonErrorCode.INVALID_REQUEST_PAYLOAD.message());
            return;
        }

        state.unsubscribe(channels, symbols);
        removeIndexes(state, channels, symbols);
        sendJson(state, new SubscriptionFrame(UNSUBSCRIBED_TYPE, requestId, channels, symbols));
        log.info("market.websocket.unsubscribe.accepted sessionId={} userId={} requestId={} channels={} symbols={} channelCount={} symbolCount={}",
                state.session().getId(),
                state.userId(),
                requestId,
                channels,
                symbols,
                channels.size(),
                symbols.size());
    }

    private void publishCurrentPriceSnapshots(SessionState state, List<String> channels, List<String> symbols) {
        if (!channels.contains(PRICE_TICK_CHANNEL)) {
            return;
        }
        int snapshotCount = 0;
        for (String symbol : symbols) {
            Optional<StandardQuote> quote = currentPriceSnapshot(symbol);
            if (quote.isEmpty()) {
                continue;
            }
            sendPriceTick(state, quote.get());
            snapshotCount++;
        }
        if (snapshotCount > 0) {
            log.info("market.websocket.price.snapshot-push sessionId={} userId={} snapshotCount={} symbolCount={}",
                    state.session().getId(),
                    state.userId(),
                    snapshotCount,
                    symbols.size());
        }
    }

    private Optional<StandardQuote> currentPriceSnapshot(String symbol) {
        Optional<StandardQuote> latestQuote = marketLatestQuoteRepository.findBySymbol(symbol);
        if (latestQuote.isPresent()) {
            return latestQuote;
        }
        return marketReferenceQuoteRepository.findBySymbol(symbol);
    }

    private void sendPriceTick(SessionState state, StandardQuote quote) {
        // STAGE-12-GROUP-MARKUP：在出口统一应用用户组 Layer 2 加点。
        // 零加点 / 不存在配置 / 禁用时 applyMarkup 直接返回原对象（零分配回退）。
        StandardQuote effective = marketGroupMarkupService.applyMarkup(quote, state.groupCode());
        // STAGE-12-GROUP-MARKUP Phase-2：同时透传基准 bid/ask/mid（不含 markup）。
        // price.tick/base* 只服务行情列表、Tick 图、下单面板、stale 状态与公允价展示；
        // K 线图只消费 REST 历史 K 线与 WSS kline.{interval}。
        boolean hasMarkup = effective != quote;
        sendJson(state, new PriceTickFrame(
                PRICE_TICK_CHANNEL,
                effective.symbol(),
                effective.bid().toPlainString(),
                effective.ask().toPlainString(),
                effective.mid().toPlainString(),
                effective.mark().toPlainString(),
                // base* 字段：始终是 markup 应用之前的基准价，不作为 K 线绘制来源。
                quote.bid().toPlainString(),
                quote.ask().toPlainString(),
                quote.mid().toPlainString(),
                hasMarkup,
                effective.ts().toString(),
                effective.source(),
                effective.stale(),
                effective.qualityStatus() == null ? null : effective.qualityStatus().name(),
                effective.qualityReason()
        ));
    }

    private void sendPong(SessionState state, String ts) {
        sendJson(state, new PongFrame(PONG_TYPE, ts));
    }

    private void sendError(SessionState state, String requestId, String code, String message) {
        sendJson(state, new ErrorFrame(ERROR_TYPE, requestId, code, message));
    }

    private void sendJson(SessionState state, Object payload) {
        try {
            state.session().sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (IOException exception) {
            log.warn("market.websocket.session.send.failed sessionId={} userId={} message={}",
                    state.session().getId(),
                    state.userId(),
                    exception.getMessage());
            unregister(state.session().getId(), CloseStatus.SERVER_ERROR, "send-failed");
        }
    }

    private void sendHeartbeat(SessionState state) {
        if (!state.session().isOpen()) {
            return;
        }
        state.lastPingAtMillis().set(System.currentTimeMillis());
        try {
            state.session().sendMessage(new PingMessage());
            withTrace(state.traceId(), () -> log.debug("market.websocket.session.ping sessionId={} userId={}",
                    state.session().getId(),
                    state.userId()));
        } catch (IOException exception) {
            withTrace(state.traceId(), () -> log.warn("market.websocket.session.ping.failed sessionId={} userId={} message={}",
                    state.session().getId(),
                    state.userId(),
                    exception.getMessage()));
            unregister(state.session().getId(), CloseStatus.SERVER_ERROR, "ping-send-failed");
        }
    }

    private void checkHeartbeat(SessionState state) {
        long lastPing = state.lastPingAtMillis().get();
        if (lastPing == 0L) {
            return;
        }
        long lastPong = state.lastPongAtMillis().get();
        long now = System.currentTimeMillis();
        if (lastPong < lastPing && now - lastPing > pongTimeout.toMillis()) {
            withTrace(state.traceId(), () -> log.warn("market.websocket.session.pong-timeout sessionId={} userId={} timeoutMillis={}",
                    state.session().getId(),
                    state.userId(),
                    pongTimeout.toMillis()));
            unregister(state.session().getId(), CloseStatus.GOING_AWAY, "pong-timeout");
        }
    }

    private List<String> normalizeChannels(List<String> rawChannels) {
        return rawChannels.stream()
                .map(channel -> channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT))
                .filter(channel -> PRICE_TICK_CHANNEL.equals(channel) || supportedKlineChannels.contains(channel))
                .distinct()
                .toList();
    }

    private List<String> resolveSymbols(List<String> requestedSymbols, String groupCode) {
        Map<String, String> tradingSymbols = tradingSymbolLookup(groupCode);
        if (requestedSymbols.isEmpty()) {
            return tradingSymbols.values().stream().sorted().toList();
        }

        List<String> resolved = requestedSymbols.stream()
                .map(symbol -> symbol == null ? "" : symbol.trim())
                .filter(symbol -> !symbol.isBlank())
                .map(symbol -> tradingSymbols.get(symbol.toUpperCase(Locale.ROOT)))
                .distinct()
                .toList();
        if (resolved.stream().anyMatch(symbol -> symbol == null)) {
            return null;
        }
        return resolved;
    }

    private Map<String, String> tradingSymbolLookup(String groupCode) {
        return marketSymbolRepository.findTradingSymbolsByGroupCode(groupCode).stream()
                .map(MarketSymbolWithSpec::symbol)
                .collect(Collectors.toMap(
                        symbol -> symbol.toUpperCase(Locale.ROOT),
                        symbol -> symbol,
                        (left, ignored) -> left,
                        LinkedHashMap::new
                ));
    }

    private Optional<String> firstUnknownSymbol(List<String> requestedSymbols, String groupCode) {
        Map<String, String> tradingSymbols = tradingSymbolLookup(groupCode);
        return requestedSymbols.stream()
                .map(symbol -> symbol == null ? "" : symbol.trim())
                .filter(symbol -> !symbol.isBlank())
                .filter(symbol -> !tradingSymbols.containsKey(symbol.toUpperCase(Locale.ROOT)))
                .findFirst();
    }

    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(child -> {
            if (child != null && child.isTextual()) {
                values.add(child.asText());
            }
        });
        return values;
    }

    private void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    int indexedSubscriptionCount(String channel, String symbol) {
        Set<String> sessionIds = channelSymbolSessionIds.get(new ChannelSymbolKey(channel, symbol));
        return sessionIds == null ? 0 : sessionIds.size();
    }

    int activeSessionCount() {
        return sessions.size();
    }

    int totalIndexedSubscriptionCount() {
        return channelSymbolSessionIds.values().stream()
                .mapToInt(Set::size)
                .sum();
    }

    int totalBufferedBytes() {
        return sessions.values().stream()
                .map(SessionState::session)
                .mapToInt(ConcurrentWebSocketSessionDecorator::getBufferSize)
                .sum();
    }

    private void registerMetrics(MeterRegistry meterRegistry) {
        if (meterRegistry == null) {
            return;
        }
        Gauge.builder("falconx.market.websocket.sessions.active", this,
                        MarketWebSocketSessionRegistry::activeSessionCount)
                .description("market-service 当前活跃 WebSocket 会话数")
                .baseUnit("sessions")
                .register(meterRegistry);
        Gauge.builder("falconx.market.websocket.subscriptions.indexed", this,
                        MarketWebSocketSessionRegistry::totalIndexedSubscriptionCount)
                .description("market-service 当前按 channel + symbol 索引的订阅关系数")
                .baseUnit("subscriptions")
                .register(meterRegistry);
        Gauge.builder("falconx.market.websocket.buffer.bytes", this,
                        MarketWebSocketSessionRegistry::totalBufferedBytes)
                .description("market-service WebSocket session decorator 当前待发送 buffer 字节数")
                .baseUnit("bytes")
                .register(meterRegistry);
    }

    private void addIndexes(SessionState state, List<String> channels, List<String> symbols) {
        for (String channel : channels) {
            for (String symbol : symbols) {
                channelSymbolSessionIds
                        .computeIfAbsent(new ChannelSymbolKey(channel, symbol), ignored -> ConcurrentHashMap.newKeySet())
                        .add(state.session().getId());
            }
        }
    }

    private void removeIndexes(SessionState state, List<ChannelSymbolKey> subscriptions) {
        for (ChannelSymbolKey subscription : subscriptions) {
            removeIndexedSession(subscription, state.session().getId());
        }
    }

    private void removeIndexes(SessionState state, List<String> channels, List<String> symbols) {
        for (String channel : channels) {
            for (String symbol : symbols) {
                removeIndexedSession(new ChannelSymbolKey(channel, symbol), state.session().getId());
            }
        }
    }

    private void removeIndexedSession(ChannelSymbolKey key, String sessionId) {
        channelSymbolSessionIds.computeIfPresent(key, (ignored, sessionIds) -> {
            sessionIds.remove(sessionId);
            return sessionIds.isEmpty() ? null : sessionIds;
        });
    }

    private void closeQuietly(WebSocketSession session, CloseStatus closeStatus) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.close(closeStatus);
        } catch (IOException ignored) {
        }
    }

    private String attribute(WebSocketSession session, String key) {
        Object value = session.getAttributes().get(key);
        return value instanceof String string ? string : null;
    }

    private String normalizeGroupCode(String groupCode) {
        return groupCode == null || groupCode.isBlank() ? "default" : groupCode.trim();
    }

    private void withTrace(String traceId, Runnable action) {
        MDC.put(TraceIdConstants.TRACE_ID_MDC_KEY, traceId);
        try {
            action.run();
        } finally {
            MDC.remove(TraceIdConstants.TRACE_ID_MDC_KEY);
        }
    }

    private record SubscriptionFrame(String type, String requestId, List<String> channels, List<String> symbols) {
    }

    private record ErrorFrame(String type, String requestId, String code, String message) {
    }

    private record PongFrame(String type, String ts) {
    }

    /**
     * STAGE-12-GROUP-MARKUP Phase-2 扩展：bid/ask/mid/mark 是当前 session group 应用 markup 后的
     * 用户口径（含商业加点）；baseBid/baseAsk/baseMid 是平台基准价（不含 markup），用于：
     * <ul>
     *   <li>行情列表、Tick 图、下单面板、stale 状态与公允价展示</li>
     *   <li>客户端校验 markup 是否应用（hasMarkup 字段直接给答案）</li>
     * </ul>
     * K 线图只消费 REST 历史 K 线与 WSS kline.{interval}，不得从 price.tick/base* 合成。
     */
    private record PriceTickFrame(String type,
                                  String symbol,
                                  String bid,
                                  String ask,
                                  String mid,
                                  String mark,
                                  String baseBid,
                                  String baseAsk,
                                  String baseMid,
                                  boolean hasMarkup,
                                  String ts,
                                  String source,
                                  boolean stale,
                                  String quoteStatus,
                                  String qualityReason) {
    }

    private record StalePriceFrame(String type,
                                   String symbol,
                                   boolean stale,
                                   String quoteStatus,
                                   String qualityReason,
                                   String ts) {
    }

    private record KlineFrame(String type,
                              String symbol,
                              String interval,
                              String open,
                              String high,
                              String low,
                              String close,
                              String volume,
                              String openTime,
                              String closeTime,
                              boolean isFinal) {
    }

    private static final class SessionState {
        private final ConcurrentWebSocketSessionDecorator session;
        private final String traceId;
        private final String userId;
        private final String uid;
        private final String status;
        private final String groupCode;
        private final ConcurrentMap<String, Set<String>> channelSymbols = new ConcurrentHashMap<>();
        private final AtomicLong lastPingAtMillis = new AtomicLong();
        private final AtomicLong lastPongAtMillis = new AtomicLong(System.currentTimeMillis());
        private volatile ScheduledFuture<?> pingFuture;
        private volatile ScheduledFuture<?> watchdogFuture;

        private SessionState(ConcurrentWebSocketSessionDecorator session,
                             String traceId,
                             String userId,
                             String uid,
                             String status,
                             String groupCode) {
            this.session = session;
            this.traceId = traceId;
            this.userId = userId;
            this.uid = uid;
            this.status = status;
            this.groupCode = groupCode;
        }

        private ConcurrentWebSocketSessionDecorator session() {
            return session;
        }

        private String traceId() {
            return traceId;
        }

        private String userId() {
            return userId;
        }

        private String uid() {
            return uid;
        }

        private String status() {
            return status;
        }

        private String groupCode() {
            return groupCode;
        }

        private AtomicLong lastPingAtMillis() {
            return lastPingAtMillis;
        }

        private AtomicLong lastPongAtMillis() {
            return lastPongAtMillis;
        }

        private void subscribe(List<String> channels, List<String> symbols) {
            channels.forEach(channel -> channelSymbols.compute(channel, (ignored, existing) -> {
                Set<String> next = existing == null ? ConcurrentHashMap.newKeySet() : existing;
                next.addAll(symbols);
                return next;
            }));
        }

        private void unsubscribe(List<String> channels, List<String> symbols) {
            channels.forEach(channel -> channelSymbols.computeIfPresent(channel, (ignored, existing) -> {
                existing.removeAll(symbols);
                return existing.isEmpty() ? null : existing;
            }));
        }

        private boolean isSubscribed(String channel, String symbol) {
            Set<String> symbols = channelSymbols.get(channel);
            return symbols != null && symbols.contains(symbol);
        }

        private Set<String> allSubscribedSymbols() {
            return channelSymbols.values().stream()
                    .flatMap(Set::stream)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        private List<ChannelSymbolKey> allSubscriptions() {
            return channelSymbols.entrySet().stream()
                    .flatMap(entry -> entry.getValue().stream()
                            .map(symbol -> new ChannelSymbolKey(entry.getKey(), symbol)))
                    .toList();
        }
    }

    private record ChannelSymbolKey(String channel, String symbol) {
    }

    private static final class WebSocketHeartbeatThreadFactory implements ThreadFactory {
        private int index;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "market-websocket-heartbeat-" + (++index));
            thread.setDaemon(true);
            return thread;
        }
    }
}
