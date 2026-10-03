package com.falconx.market.provider;

import com.falconx.infrastructure.kafka.KafkaEventMessageSupport;
import com.falconx.infrastructure.trace.TraceIdSupport;
import com.falconx.market.config.MarketServiceProperties;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import io.socket.engineio.client.EngineIOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 基于 Socket.IO v4 的自建 LP 实时行情 Provider。
 */
@Component
@Profile("!stub")
public class SocketIoLpMarketQuoteProvider implements MarketQuoteProvider, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SocketIoLpMarketQuoteProvider.class);

    /**
     * 2026-05-21 OOM fix: LP dispatch executor 任务队列上限。
     *
     * <p>LP 推送速率 ~600 quote/s，单线程下游处理（quoteConsumer → Redis + Kafka + WS + ClickHouse
     * batch + kline aggregate）任一环节有抖动就堆积。原 {@code Executors.newSingleThreadExecutor()}
     * 默认 LinkedBlockingQueue 无上限，11 小时 OOM（前 commit 69077d3 只修了下游 pendingQuoteTicks，
     * 没改这里）。
     *
     * <p>选 10000：LP 600 q/s × ~16s 容忍窗口，足够吸收典型 GC pause / Kafka 重连等短暂抖动；
     * 满了走 drop-oldest（队首 poll 掉再 offer 新任务），与 {@code MybatisClickHouseMarketAnalyticsWriter}
     * 的 pendingQuoteTicks 队列保持一致策略。
     */
    private static final int DISPATCH_QUEUE_CAPACITY = 10_000;

    private final MarketServiceProperties properties;
    private final LpWebSocketProtocolSupport protocolSupport;
    private final BlockingQueue<Runnable> dispatchQueue;
    private final ExecutorService quoteDispatchExecutor;
    private final AtomicLong droppedDispatchTaskCount = new AtomicLong(0);
    /**
     * 最近一次成功向 quoteConsumer 分发报价的时间戳（epoch millis），未分发过为 0。
     *
     * <p>PROD-OPS-EVIDENCE-01 C2：纯观测埋点，仅供 {@code LpSocketIoHealthIndicator}
     * 判断 LP 是否在持续推送有效行情（连上但久无报价 = stale），不参与任何分发/线程逻辑。
     */
    private final AtomicLong lastQuoteAtMillis = new AtomicLong(0);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicReference<Socket> activeSocket = new AtomicReference<>();

    private volatile List<String> subscribedSymbols = List.of();
    private volatile Set<String> subscribedSymbolSet = Set.of();
    private volatile Consumer<ExternalRawQuote> quoteConsumer;

    public SocketIoLpMarketQuoteProvider(MarketServiceProperties properties,
                                         LpWebSocketProtocolSupport protocolSupport) {
        this.properties = properties;
        this.protocolSupport = protocolSupport;
        this.dispatchQueue = new ArrayBlockingQueue<>(DISPATCH_QUEUE_CAPACITY);
        RejectedExecutionHandler dropOldest = (rejected, exec) -> {
            // 队列满时丢弃队首最老任务（与下游 pendingQuoteTicks 一致：行情数据老化快，宁可丢历史保新数据），
            // 再尝试 offer 新任务；offer 失败则连新任务也丢，确保不阻塞 LP socket 回调线程。
            Runnable removed = exec.getQueue().poll();
            long dropped = droppedDispatchTaskCount.incrementAndGet();
            if (removed == null || !exec.getQueue().offer(rejected)) {
                droppedDispatchTaskCount.incrementAndGet();
            }
            // 每 1000 次 drop 输出一行 WARN，避免日志爆炸但能感知积压
            if (dropped % 1000 == 1) {
                log.warn("market.lp.provider.dispatch.queue.dropped total={} capacity={} queueSize={}",
                        dropped, DISPATCH_QUEUE_CAPACITY, exec.getQueue().size());
            }
        };
        this.quoteDispatchExecutor = new ThreadPoolExecutor(
                1, 1,
                0L, TimeUnit.MILLISECONDS,
                this.dispatchQueue,
                Thread.ofPlatform()
                        .name("market-lp-dispatch-", 0)
                        .daemon(true)
                        .factory(),
                dropOldest
        );
    }

    /** 当前已丢弃的 dispatch 任务计数（供 actuator/监控读取，零运行成本）。 */
    public long getDroppedDispatchTaskCount() {
        return droppedDispatchTaskCount.get();
    }

    /** 当前 dispatch 队列水位（供 actuator/监控读取）。 */
    public int getDispatchQueueSize() {
        return dispatchQueue.size();
    }

    /**
     * 当前 LP socket 是否已连接（供健康指标读取）。
     *
     * <p>提取自原 {@code refreshSymbols} 内已有的判断逻辑
     * {@code currentSocket != null && currentSocket.connected()}。
     */
    public boolean isLpConnected() {
        Socket currentSocket = activeSocket.get();
        return currentSocket != null && currentSocket.connected();
    }

    /**
     * 最近一次成功向下游分发报价的时间戳（epoch millis）；从未分发过返回 0。
     */
    public long lastQuoteEpochMillis() {
        return lastQuoteAtMillis.get();
    }

    @Override
    public void start(List<String> symbols, Consumer<ExternalRawQuote> quoteConsumer) {
        if (!started.compareAndSet(false, true)) {
            log.info("market.lp.provider.already-started");
            return;
        }
        this.quoteConsumer = Objects.requireNonNull(quoteConsumer, "quoteConsumer");
        this.subscribedSymbols = normalizeSymbols(symbols);
        this.subscribedSymbolSet = Set.copyOf(this.subscribedSymbols);

        if (!properties.getLp().isEnabled()) {
            log.warn("market.lp.provider.disabled reason=config-disabled");
            return;
        }
        if (!hasRequiredCredentials()) {
            log.warn("market.lp.provider.disabled reason=missing-credentials");
            return;
        }
        if (subscribedSymbols.isEmpty()) {
            log.warn("market.lp.provider.disabled reason=no-supported-symbols");
            return;
        }

        connect();
    }

    @Override
    public void refreshSymbols(List<String> symbols) {
        List<String> normalizedSymbols = normalizeSymbols(symbols);
        Set<String> newSymbolSet = Set.copyOf(normalizedSymbols);
        Set<String> previousSymbols = subscribedSymbolSet;
        if (previousSymbols.equals(newSymbolSet)) {
            log.debug("market.lp.provider.symbols.unchanged count={}", normalizedSymbols.size());
            return;
        }

        List<String> addedSymbols = normalizedSymbols.stream()
                .filter(symbol -> !previousSymbols.contains(symbol))
                .toList();
        List<String> removedSymbols = subscribedSymbols.stream()
                .filter(symbol -> !newSymbolSet.contains(symbol))
                .toList();

        this.subscribedSymbols = normalizedSymbols;
        this.subscribedSymbolSet = newSymbolSet;
        log.info(
                "market.lp.provider.symbols.refreshed total={} added={} removed={} addedSample={} removedSample={}",
                normalizedSymbols.size(),
                addedSymbols.size(),
                removedSymbols.size(),
                sampleSymbols(addedSymbols),
                sampleSymbols(removedSymbols)
        );
        Socket currentSocket = activeSocket.get();
        if (currentSocket != null && currentSocket.connected()) {
            sendSubscribeMessage(currentSocket);
        }
    }

    private void connect() {
        URI socketUri = protocolSupport.buildSocketUri(properties.getLp());
        SocketIoConnectionTarget connectionTarget = toSocketIoConnectionTarget(socketUri);
        log.info("market.lp.provider.connecting domain={} symbolCount={} sampleSymbols={}",
                properties.getLp().getDomain(),
                subscribedSymbols.size(),
                sampleSymbols(subscribedSymbols));

        IO.Options options = new IO.Options();
        options.transports = new String[]{"websocket"};
        options.upgrade = false;
        options.reconnection = false;
        options.timeout = properties.getLp().getConnectTimeout().toMillis();
        options.path = connectionTarget.enginePath();
        options.query = connectionTarget.query();

        Socket socket = IO.socket(connectionTarget.clientUri(), options);
        socket.on(Socket.EVENT_CONNECT, onConnect(socket));
        socket.on(Socket.EVENT_CONNECT_ERROR, onConnectError());
        socket.on(Socket.EVENT_DISCONNECT, onDisconnect(socket));
        socket.on("price-compression", onPriceCompression());
        activeSocket.set(socket);
        socket.connect();
    }

    static SocketIoConnectionTarget toSocketIoConnectionTarget(URI fullUri) {
        Objects.requireNonNull(fullUri, "fullUri");
        String path = fullUri.getRawPath();
        String enginePath = (path == null || path.isBlank() || "/".equals(path)) ? "/socket.io/" : path;
        if (!enginePath.endsWith("/")) {
            enginePath = enginePath + "/";
        }
        String query = fullUri.getRawQuery();
        String clientUri = fullUri.getScheme() + "://" + fullUri.getRawAuthority() + "/";
        return new SocketIoConnectionTarget(URI.create(clientUri), enginePath, query);
    }

    static String describeConnectError(Object reason) {
        if (reason == null) {
            return "unknown";
        }
        if (reason instanceof EngineIOException engineIOException) {
            StringBuilder description = new StringBuilder(throwableSummary(engineIOException));
            if (engineIOException.transport != null) {
                description.append(" transport=").append(sanitizeReason(engineIOException.transport));
            }
            if (engineIOException.code != null) {
                description.append(" code=").append(sanitizeReason(String.valueOf(engineIOException.code)));
            }
            Throwable cause = engineIOException.getCause();
            if (cause != null) {
                description.append(" cause=").append(throwableSummary(cause));
            }
            return sanitizeReason(description.toString());
        }
        if (reason instanceof Throwable throwable) {
            return throwableSummary(throwable);
        }
        return sanitizeReason(String.valueOf(reason));
    }

    private Emitter.Listener onConnect(Socket socket) {
        return args -> {
            log.info("market.lp.provider.connected domain={}", properties.getLp().getDomain());
            sendSubscribeMessage(socket);
        };
    }

    private Emitter.Listener onConnectError() {
        return args -> {
            Object reason = args == null || args.length == 0 ? null : args[0];
            log.warn("market.lp.provider.connect.failed reason={}", describeConnectError(reason));
            scheduleReconnect("connect-failed");
        };
    }

    private Emitter.Listener onDisconnect(Socket socket) {
        return args -> {
            activeSocket.compareAndSet(socket, null);
            String reason = args == null || args.length == 0 ? "unknown" : String.valueOf(args[0]);
            log.warn("market.lp.provider.disconnected reason={}", sanitizeReason(reason));
            scheduleReconnect("disconnect-" + sanitizeReason(reason));
        };
    }

    private Emitter.Listener onPriceCompression() {
        return args -> {
            if (args == null || args.length == 0) {
                log.warn("market.lp.provider.price-compression.empty");
                return;
            }
            // 2026-05-26 性能加固（Sprint 2 S4 / 性能分析报告 §4 P0）：
            // 原实现把 decompressPriceFrame + parseQuotes 放在 socket.io 回调线程做。
            // GZIP 解压 + JSON 解析是 CPU 重活，回调线程被占就不能响应下一帧，
            // 高频时 socket.io 事件队列堆积、跳帧。
            // 改为：回调线程只持有 args[0] 引用提交到 quoteDispatchExecutor；
            // decompress + parse + summarize + dispatch 全在 executor 线程跑。
            // 注：args[0] 是 socket.io 内部已解析好的 payload 对象（ByteBuffer / byte[]
            // / String 之一，protocolSupport.decompressPriceFrame 自行兼容），
            // socket.io 客户端在回调返回后不会复用该对象，无需 deep copy。
            Object rawFrame = args[0];
            try {
                quoteDispatchExecutor.execute(() -> runWithApplicationClassLoader(() -> processPriceCompressionFrame(rawFrame)));
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                log.warn("market.lp.provider.price-compression.rejected reason={}", rejected.toString());
            }
        };
    }

    private void processPriceCompressionFrame(Object rawFrame) {
        try {
            String quoteJson = protocolSupport.decompressPriceFrame(rawFrame);
            List<ExternalRawQuote> quotes = protocolSupport.parseQuotes(quoteJson);
            if (quotes.isEmpty()) {
                log.debug("market.lp.provider.price-compression.parsed quotes=0");
                return;
            }
            DispatchSummary dispatchSummary = summarizeDispatch(quotes, subscribedSymbolSet);
            log.debug("market.lp.provider.price-compression.parsed quotes={} accepted={} filtered={}",
                    quotes.size(),
                    dispatchSummary.acceptedQuotes().size(),
                    dispatchSummary.filteredCount());
            dispatchSummary.acceptedQuotes().forEach(this::dispatchQuote);
        } catch (RuntimeException error) {
            log.warn("market.lp.provider.price-compression.failed reason={}", error.toString(), error);
        }
    }

    private void sendSubscribeMessage(Socket socket) {
        int serverId = properties.getLp().getServerId();
        String payload = protocolSupport.buildSubscribePayload(serverId, subscribedSymbols);
        socket.emit("external-sub-symbol", toSubscribeEventPayload(payload));
        log.info("market.lp.provider.subscribed serverId={} symbolCount={} sampleSymbols={}",
                serverId,
                subscribedSymbols.size(),
                sampleSymbols(subscribedSymbols));
    }

    static Object toSubscribeEventPayload(String payload) {
        return payload;
    }

    private void scheduleReconnect(String reason) {
        if (!started.get()) {
            return;
        }
        if (!reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        log.warn("market.lp.provider.reconnect.scheduled reason={} delay={}",
                sanitizeReason(reason),
                properties.getLp().getReconnectInterval());
        CompletableFuture.delayedExecutor(properties.getLp().getReconnectInterval().toMillis(), TimeUnit.MILLISECONDS)
                .execute(() -> {
                    reconnectScheduled.set(false);
                    if (!started.get()) {
                        return;
                    }
                    closeActiveSocket();
                    connect();
                });
    }

    private void dispatchQuote(ExternalRawQuote quote) {
        String traceId = TraceIdSupport.newTraceId();
        quoteDispatchExecutor.execute(() -> runWithApplicationClassLoader(() -> {
            KafkaEventMessageSupport.bindTraceId(traceId);
            ExternalRawQuote platformTimedQuote = withPlatformTimestamp(quote, OffsetDateTime.now(ZoneOffset.UTC));
            try {
                quoteConsumer.accept(platformTimedQuote);
                // 成功分发才更新埋点：accept 抛错时不刷新，使 health 能反映“连上但下游持续失败”。
                lastQuoteAtMillis.set(System.currentTimeMillis());
            } catch (Throwable error) {
                if (error instanceof VirtualMachineError virtualMachineError) {
                    throw virtualMachineError;
                }
                log.error("market.lp.provider.dispatch.failed ticker={} ts={} reason={}",
                        platformTimedQuote.ticker(),
                        platformTimedQuote.ts(),
                        error.toString(),
                        error);
            } finally {
                KafkaEventMessageSupport.clearTraceId();
            }
        }));
    }

    static ExternalRawQuote withPlatformTimestamp(ExternalRawQuote quote, OffsetDateTime timestamp) {
        return new ExternalRawQuote(
                quote.ticker(),
                quote.bid(),
                quote.ask(),
                timestamp,
                quote.source()
        );
    }

    static void runWithApplicationClassLoader(Runnable runnable) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader applicationClassLoader = SocketIoLpMarketQuoteProvider.class.getClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(applicationClassLoader);
            runnable.run();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    static DispatchSummary summarizeDispatch(List<ExternalRawQuote> quotes, Set<String> subscribedSymbols) {
        if (quotes == null || quotes.isEmpty()) {
            return new DispatchSummary(List.of(), 0);
        }
        List<ExternalRawQuote> acceptedQuotes = new ArrayList<>();
        int filteredCount = 0;
        for (ExternalRawQuote quote : quotes) {
            if (quote != null && subscribedSymbols.contains(quote.ticker())) {
                acceptedQuotes.add(quote);
            } else {
                filteredCount++;
            }
        }
        return new DispatchSummary(List.copyOf(acceptedQuotes), filteredCount);
    }

    private boolean hasRequiredCredentials() {
        return hasRequiredSocketConnectionConfig(properties.getLp());
    }

    static boolean hasRequiredSocketConnectionConfig(MarketServiceProperties.Lp lp) {
        if (lp == null) {
            return false;
        }
        return hasText(lp.getDomain())
                && hasText(lp.getAppId())
                && hasText(lp.getToken())
                && hasText(lp.getSecretKey())
                && lp.getServerId() > 0;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<String> normalizeSymbols(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank()) {
                continue;
            }
            String normalizedSymbol = symbol.trim();
            if (!normalized.contains(normalizedSymbol)) {
                normalized.add(normalizedSymbol);
            }
        }
        return List.copyOf(normalized);
    }

    private static String throwableSummary(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return sanitizeReason(throwable.getClass().getSimpleName());
        }
        return sanitizeReason(throwable.getClass().getSimpleName() + ": " + message);
    }

    private static String sanitizeReason(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return value.replaceAll("(?i)(APP-ID|Authorization|signature|encrypt|nonce|timestamp)=([^&\\s]+)", "$1=[REDACTED]")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('[', '(')
                .replace(']', ')');
    }

    private void closeActiveSocket() {
        Socket currentSocket = activeSocket.getAndSet(null);
        if (currentSocket != null) {
            currentSocket.disconnect();
            currentSocket.close();
        }
    }

    @Override
    public void destroy() {
        started.set(false);
        closeActiveSocket();
        quoteDispatchExecutor.shutdownNow();
    }

    record DispatchSummary(List<ExternalRawQuote> acceptedQuotes, int filteredCount) {
    }

    private static List<String> sampleSymbols(Collection<String> symbols) {
        return symbols.stream().limit(10).toList();
    }

    record SocketIoConnectionTarget(URI clientUri, String enginePath, String query) {
    }
}
