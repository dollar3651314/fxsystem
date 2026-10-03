package com.falconx.trading.websocket;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.falconx.common.error.CommonErrorCode;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.infrastructure.trace.TraceIdSupport;
import com.falconx.trading.application.TradingAccountSnapshotApplicationService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * 用户交易实时 WebSocket 会话注册表。
 */
@Component
public class TradingUserWebSocketSessionRegistry {

    public static final String CHANNEL_ACCOUNT = "account";
    public static final String CHANNEL_ORDERS = "orders";
    public static final String CHANNEL_POSITIONS = "positions";
    public static final String CHANNEL_TRADES = "trades";
    public static final String CHANNEL_MARGIN = "margin";
    public static final String CHANNEL_LEDGER = "ledger";
    public static final String CHANNEL_LIQUIDATIONS = "liquidations";
    // STAGE-4-PRICE-ALERT：价格告警推送
    public static final String CHANNEL_PRICE_ALERTS = "price-alerts";
    // STAGE-8-NOTIFICATION：站内信推送
    public static final String CHANNEL_NOTIFICATIONS = "notifications";
    // STAGE-2-REALTIME-DATA Phase 2：管理端频道
    public static final String CHANNEL_ADMIN_EXPOSURE = "admin.exposure";
    public static final String CHANNEL_ADMIN_RISK_ACTIONS = "admin.risk-actions";
    public static final String CHANNEL_ADMIN_RISK_SWITCHES = "admin.risk-switches";
    // STAGE-7-WITHDRAW Phase 4 §4 commit C: 出金状态实时推送（PROCESSING/COMPLETED/FAILED）
    public static final String CHANNEL_ADMIN_WITHDRAWS = "admin.withdraws";
    // 管理端持仓 PnL 实时推送（admin.position.update，按 symbol 200ms 节流）
    public static final String CHANNEL_ADMIN_POSITIONS = "admin.positions";

    private static final Logger log = LoggerFactory.getLogger(TradingUserWebSocketSessionRegistry.class);
    private static final Set<String> SUPPORTED_USER_CHANNELS = Set.of(
            CHANNEL_ACCOUNT,
            CHANNEL_ORDERS,
            CHANNEL_POSITIONS,
            CHANNEL_TRADES,
            CHANNEL_MARGIN,
            CHANNEL_LEDGER,
            CHANNEL_LIQUIDATIONS,
            CHANNEL_PRICE_ALERTS,
            CHANNEL_NOTIFICATIONS
    );
    private static final Set<String> SUPPORTED_ADMIN_CHANNELS = Set.of(
            CHANNEL_ADMIN_EXPOSURE,
            CHANNEL_ADMIN_RISK_ACTIONS,
            CHANNEL_ADMIN_RISK_SWITCHES,
            CHANNEL_ADMIN_WITHDRAWS,
            CHANNEL_ADMIN_POSITIONS
    );

    private final ObjectMapper objectMapper;
    private final TradingAccountSnapshotApplicationService accountSnapshotApplicationService;
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<UserChannelKey, Set<String>> userChannelSessionIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> adminChannelSessionIds = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor pushExecutor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            new TradingWebSocketPushThreadFactory()
    );

    @Autowired
    public TradingUserWebSocketSessionRegistry(ObjectMapper objectMapper,
                                               TradingAccountSnapshotApplicationService accountSnapshotApplicationService,
                                               ObjectProvider<MeterRegistry> meterRegistry) {
        this(objectMapper, accountSnapshotApplicationService, meterRegistry.getIfAvailable());
    }

    TradingUserWebSocketSessionRegistry(ObjectMapper objectMapper,
                                        TradingAccountSnapshotApplicationService accountSnapshotApplicationService) {
        this(objectMapper, accountSnapshotApplicationService, (MeterRegistry) null);
    }

    TradingUserWebSocketSessionRegistry(ObjectMapper objectMapper,
                                        TradingAccountSnapshotApplicationService accountSnapshotApplicationService,
                                        MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.accountSnapshotApplicationService = accountSnapshotApplicationService;
        registerMetrics(meterRegistry);
    }

    public void register(WebSocketSession rawSession) {
        String traceId = TraceIdSupport.reuseOrCreate(attribute(rawSession, TradingUserWebSocketHandshakeInterceptor.ATTRIBUTE_TRACE_ID));
        Long adminUserId = parseUserId(attribute(rawSession, TradingUserWebSocketHandshakeInterceptor.ATTRIBUTE_ADMIN_USER_ID));
        Long userId = parseUserId(attribute(rawSession, TradingUserWebSocketHandshakeInterceptor.ATTRIBUTE_USER_ID));
        if (adminUserId == null && userId == null) {
            closeQuietly(rawSession, CloseStatus.POLICY_VIOLATION);
            return;
        }
        ConcurrentWebSocketSessionDecorator session = new ConcurrentWebSocketSessionDecorator(rawSession, 10_000, 512 * 1024);
        boolean admin = adminUserId != null;
        SessionState state = new SessionState(
                session,
                traceId,
                admin ? adminUserId : userId,
                admin,
                attribute(rawSession, TradingUserWebSocketHandshakeInterceptor.ATTRIBUTE_UID),
                attribute(rawSession, TradingUserWebSocketHandshakeInterceptor.ATTRIBUTE_STATUS),
                ConcurrentHashMap.newKeySet(),
                new AtomicLong(System.currentTimeMillis())
        );
        sessions.put(session.getId(), state);
        withTrace(state.traceId(), () -> log.info("trading.websocket.session.opened sessionId={} principalId={} admin={} uid={} status={} activeSessions={}",
                session.getId(),
                state.principalId(),
                state.admin(),
                state.uid(),
                state.status(),
                sessions.size()));
        if (!admin) {
            sendJson(state, new TradingUserWebSocketEnvelope(
                    "account.snapshot",
                    CHANNEL_ACCOUNT,
                    null,
                    accountSnapshotApplicationService.getCurrentAccountSnapshot(userId),
                    OffsetDateTime.now()
            ));
        }
    }

    public void unregister(String sessionId, CloseStatus closeStatus, String reason) {
        SessionState state = sessions.remove(sessionId);
        if (state == null) {
            return;
        }
        removeIndexes(state, List.copyOf(state.channels()));
        closeQuietly(state.session(), closeStatus);
        withTrace(state.traceId(), () -> log.info("trading.websocket.session.closed sessionId={} principalId={} admin={} closeCode={} reason={} activeSessions={}",
                sessionId,
                state.principalId(),
                state.admin(),
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
                    case "ping" -> sendPong(state, root.path("requestId").asText(null), root.path("ts").asText(""));
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
        withTrace(state.traceId(), () -> log.debug("trading.websocket.session.pong sessionId={} principalId={}",
                sessionId,
                state.principalId()));
    }

    public int broadcast(Long userId, String channel, TradingUserWebSocketEnvelope envelope) {
        if (userId == null || channel == null || channel.isBlank()) {
            return 0;
        }
        Set<String> sessionIds = userChannelSessionIds.get(new UserChannelKey(userId, channel));
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }
        int recipients = 0;
        for (String sessionId : sessionIds) {
            SessionState recipient = sessions.get(sessionId);
            if (recipient == null || recipient.admin()
                    || !recipient.principalId().equals(userId)
                    || !recipient.isSubscribed(channel)) {
                sessionIds.remove(sessionId);
                continue;
            }
            recipients++;
            pushExecutor.execute(() -> sendJson(recipient, envelope));
        }
        return recipients;
    }

    /**
     * STAGE-2-REALTIME-DATA Phase 2：向所有 admin 会话广播。
     * 不按 adminUserId 过滤——admin 看板事件对所有 admin 都可见。
     */
    public int broadcastToAdmins(String channel, TradingUserWebSocketEnvelope envelope) {
        if (channel == null || channel.isBlank()) {
            return 0;
        }
        Set<String> sessionIds = adminChannelSessionIds.get(channel);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }
        int recipients = 0;
        for (String sessionId : sessionIds) {
            SessionState recipient = sessions.get(sessionId);
            if (recipient == null || !recipient.admin() || !recipient.isSubscribed(channel)) {
                sessionIds.remove(sessionId);
                continue;
            }
            recipients++;
            pushExecutor.execute(() -> sendJson(recipient, envelope));
        }
        return recipients;
    }

    @PreDestroy
    public void shutdown() {
        pushExecutor.shutdownNow();
    }

    private void handleSubscribe(SessionState state, JsonNode root) {
        String requestId = root.path("requestId").asText(null);
        List<String> channels = normalizeChannels(readStringArray(root.get("channels")));
        if (channels.isEmpty()) {
            sendError(state, requestId,
                    CommonErrorCode.INVALID_REQUEST_PAYLOAD.code(),
                    CommonErrorCode.INVALID_REQUEST_PAYLOAD.message());
            return;
        }
        state.subscribe(channels);
        addIndexes(state, channels);
        sendJson(state, new TradingUserWebSocketEnvelope(
                "subscribed",
                null,
                requestId,
                Map.of("channels", channels),
                OffsetDateTime.now()
        ));
        log.info("trading.websocket.subscribe.accepted sessionId={} principalId={} admin={} requestId={} channels={} channelCount={}",
                state.session().getId(),
                state.principalId(),
                state.admin(),
                requestId,
                channels,
                channels.size());
    }

    private void handleUnsubscribe(SessionState state, JsonNode root) {
        String requestId = root.path("requestId").asText(null);
        List<String> channels = normalizeChannels(readStringArray(root.get("channels")));
        if (channels.isEmpty()) {
            sendError(state, requestId,
                    CommonErrorCode.INVALID_REQUEST_PAYLOAD.code(),
                    CommonErrorCode.INVALID_REQUEST_PAYLOAD.message());
            return;
        }
        state.unsubscribe(channels);
        removeIndexes(state, channels);
        sendJson(state, new TradingUserWebSocketEnvelope(
                "unsubscribed",
                null,
                requestId,
                Map.of("channels", channels),
                OffsetDateTime.now()
        ));
        log.info("trading.websocket.unsubscribe.accepted sessionId={} principalId={} admin={} requestId={} channels={} channelCount={}",
                state.session().getId(),
                state.principalId(),
                state.admin(),
                requestId,
                channels,
                channels.size());
    }

    private void sendPong(SessionState state, String requestId, String ts) {
        sendJson(state, new TradingUserWebSocketEnvelope(
                "pong",
                null,
                requestId,
                Map.of("ts", ts),
                OffsetDateTime.now()
        ));
    }

    private void sendError(SessionState state, String requestId, String code, String message) {
        sendJson(state, new TradingUserWebSocketEnvelope(
                "error",
                null,
                requestId,
                Map.of("code", code, "message", message),
                OffsetDateTime.now()
        ));
    }

    private void sendJson(SessionState state, Object payload) {
        try {
            if (!state.session().isOpen()) {
                return;
            }
            state.session().sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (IOException exception) {
            log.warn("trading.websocket.session.send.failed sessionId={} principalId={} admin={} message={}",
                    state.session().getId(),
                    state.principalId(),
                    state.admin(),
                    exception.getMessage());
            unregister(state.session().getId(), CloseStatus.SERVER_ERROR, "send-failed");
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus closeStatus) {
        try {
            if (session.isOpen()) {
                session.close(closeStatus);
            }
        } catch (IOException exception) {
            log.debug("trading.websocket.session.close.failed sessionId={} message={}",
                    session.getId(),
                    exception.getMessage());
        }
    }

    private String attribute(WebSocketSession session, String name) {
        Object value = session.getAttributes().get(name);
        return value == null ? null : String.valueOf(value);
    }

    private Long parseUserId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private List<String> normalizeChannels(List<String> channels) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String channel : channels) {
            if (channel == null) {
                continue;
            }
            String value = channel.trim().toLowerCase(Locale.ROOT);
            if (SUPPORTED_USER_CHANNELS.contains(value) || SUPPORTED_ADMIN_CHANNELS.contains(value)) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    int indexedUserSubscriptionCount(Long userId, String channel) {
        Set<String> sessionIds = userChannelSessionIds.get(new UserChannelKey(userId, channel));
        return sessionIds == null ? 0 : sessionIds.size();
    }

    int indexedAdminSubscriptionCount(String channel) {
        Set<String> sessionIds = adminChannelSessionIds.get(channel);
        return sessionIds == null ? 0 : sessionIds.size();
    }

    int activeSessionCount() {
        return sessions.size();
    }

    int totalIndexedUserSubscriptionCount() {
        return userChannelSessionIds.values().stream()
                .mapToInt(Set::size)
                .sum();
    }

    int totalIndexedAdminSubscriptionCount() {
        return adminChannelSessionIds.values().stream()
                .mapToInt(Set::size)
                .sum();
    }

    int pushQueueSize() {
        return pushExecutor.getQueue().size();
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
        Gauge.builder("falconx.trading.websocket.sessions.active", this,
                        TradingUserWebSocketSessionRegistry::activeSessionCount)
                .description("trading-core 当前活跃 WebSocket 会话数")
                .baseUnit("sessions")
                .register(meterRegistry);
        Gauge.builder("falconx.trading.websocket.user.subscriptions.indexed", this,
                        TradingUserWebSocketSessionRegistry::totalIndexedUserSubscriptionCount)
                .description("trading-core 当前按 userId + channel 索引的用户订阅关系数")
                .baseUnit("subscriptions")
                .register(meterRegistry);
        Gauge.builder("falconx.trading.websocket.admin.subscriptions.indexed", this,
                        TradingUserWebSocketSessionRegistry::totalIndexedAdminSubscriptionCount)
                .description("trading-core 当前按 admin channel 索引的管理端订阅关系数")
                .baseUnit("subscriptions")
                .register(meterRegistry);
        Gauge.builder("falconx.trading.websocket.push.queue.size", this,
                        TradingUserWebSocketSessionRegistry::pushQueueSize)
                .description("trading-core WebSocket 单线程推送 executor 当前排队任务数")
                .baseUnit("tasks")
                .register(meterRegistry);
        Gauge.builder("falconx.trading.websocket.buffer.bytes", this,
                        TradingUserWebSocketSessionRegistry::totalBufferedBytes)
                .description("trading-core WebSocket session decorator 当前待发送 buffer 字节数")
                .baseUnit("bytes")
                .register(meterRegistry);
    }

    private void addIndexes(SessionState state, List<String> channels) {
        for (String channel : channels) {
            if (state.admin()) {
                adminChannelSessionIds
                        .computeIfAbsent(channel, ignored -> ConcurrentHashMap.newKeySet())
                        .add(state.session().getId());
            } else {
                userChannelSessionIds
                        .computeIfAbsent(new UserChannelKey(state.principalId(), channel),
                                ignored -> ConcurrentHashMap.newKeySet())
                        .add(state.session().getId());
            }
        }
    }

    private void removeIndexes(SessionState state, List<String> channels) {
        for (String channel : channels) {
            if (state.admin()) {
                removeIndexedSession(adminChannelSessionIds, channel, state.session().getId());
            } else {
                removeIndexedSession(userChannelSessionIds,
                        new UserChannelKey(state.principalId(), channel),
                        state.session().getId());
            }
        }
    }

    private static <K> void removeIndexedSession(ConcurrentMap<K, Set<String>> index, K key, String sessionId) {
        index.computeIfPresent(key, (ignored, sessionIds) -> {
            sessionIds.remove(sessionId);
            return sessionIds.isEmpty() ? null : sessionIds;
        });
    }

    private void withTrace(String traceId, Runnable action) {
        MDC.put(TraceIdConstants.TRACE_ID_MDC_KEY, traceId);
        try {
            action.run();
        } finally {
            MDC.remove(TraceIdConstants.TRACE_ID_MDC_KEY);
        }
    }

    private record SessionState(ConcurrentWebSocketSessionDecorator session,
                                String traceId,
                                Long principalId,
                                boolean admin,
                                String uid,
                                String status,
                                Set<String> channels,
                                AtomicLong lastPongAtMillis) {

        void subscribe(List<String> requestedChannels) {
            channels.addAll(requestedChannels);
        }

        void unsubscribe(List<String> requestedChannels) {
            channels.removeAll(requestedChannels);
        }

        boolean isSubscribed(String channel) {
            return channels.contains(channel);
        }
    }

    private record UserChannelKey(Long userId, String channel) {
    }

    private static final class TradingWebSocketPushThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "trading-user-ws-push-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
