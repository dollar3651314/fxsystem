package com.falconx.gateway.websocket;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * gateway WebSocket 代理（pass-through bridge）层的 bridge 级可观测指标。
 *
 * <p>PROD-OPS-EVIDENCE-01 切片 C3：补齐 market-service / trading-core-service 已有的服务内
 * WebSocket 指标在 gateway 代理层的盲区。gateway 是 Spring Cloud Gateway / WebFlux 响应式
 * pass-through 桥接，没有 servlet 式 {@code ConcurrentWebSocketSessionDecorator} 那样的显式
 * buffer。
 *
 * <p><strong>偏差披露：</strong>Reactor pipeline 的真实「待转发 backlog / pending demand」
 * 无法安全、低风险地从 {@code Flux}/{@code Mono} 链路中取出（需自定义 {@code Subscriber} 介入
 * 背压，风险高且会改桥接语义）。故本组件不暴露真 backlog，而是暴露安全且有运营价值的 bridge 级
 * 指标：活跃 bridge 数（gauge）与按方向计数的成功转发帧数（counter）。运营侧用「活跃 bridge 数
 * 异常突增 / 转发帧速率与活跃 bridge 不匹配」做近似的拥塞判据。
 *
 * <p>所有埋点挂在 handler 已有的响应式生命周期钩子（{@code doOnSubscribe}/{@code doFinally}/
 * {@code map}）上，increment 与 decrement 成对，异常与正常关闭都经 {@code doFinally} 触发，避免
 * active gauge 只增不减泄漏。tag 仅 {@code upstream}/{@code direction}（固定低基数），不记录
 * URL/token/sessionId/payload（§3.13.5）。
 */
@Component
public class GatewayWebSocketProxyMetrics {

    static final String UPSTREAM_MARKET = "market";
    static final String UPSTREAM_TRADING = "trading";
    static final String DIRECTION_CLIENT_TO_UPSTREAM = "client_to_upstream";
    static final String DIRECTION_UPSTREAM_TO_CLIENT = "upstream_to_client";

    private static final String SESSIONS_ACTIVE_METRIC = "falconx.gateway.websocket.proxy.sessions.active";
    private static final String MESSAGES_TOTAL_METRIC = "falconx.gateway.websocket.proxy.messages.total";

    private final AtomicInteger marketActiveBridges = new AtomicInteger();
    private final AtomicInteger tradingActiveBridges = new AtomicInteger();
    private ForwardCounters forwardCounters;

    // 显式 @Autowired 指定 Spring 注入构造器：本类有两个构造器（另一个包级仅供单测），
    // 多构造器且无 @Autowired 时 Spring 无法选择会回退找无参构造器并失败（No default constructor found）。
    @Autowired
    public GatewayWebSocketProxyMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        this(meterRegistry.getIfAvailable());
    }

    GatewayWebSocketProxyMetrics(MeterRegistry meterRegistry) {
        registerGauges(meterRegistry);
    }

    /**
     * bridge 建立时调用（挂在 {@code handle(...)} 链上的 {@code doOnSubscribe}）。
     */
    void onBridgeStart(String upstream) {
        bridgeCounter(upstream).incrementAndGet();
    }

    /**
     * bridge 终止时调用（挂在 {@code handle(...)} 链上已有的 {@code doFinally}，异常 / 正常关闭
     * 都会触发，保证与 {@link #onBridgeStart(String)} 成对）。结果不会小于 0。
     */
    void onBridgeEnd(String upstream) {
        AtomicInteger counter = bridgeCounter(upstream);
        counter.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    /**
     * 每成功转发一帧（TEXT/PING/PONG）调用一次（挂在 {@code mapMessage} 成功映射后的转发链上）。
     * default→{@code Mono.empty()} 的丢弃帧不计数。
     */
    void onMessageForwarded(String upstream, String direction) {
        if (forwardCounters == null) {
            return;
        }
        forwardCounters.counter(upstream, direction).increment();
    }

    int activeBridges(String upstream) {
        return bridgeCounter(upstream).get();
    }

    private AtomicInteger bridgeCounter(String upstream) {
        return UPSTREAM_TRADING.equals(upstream) ? tradingActiveBridges : marketActiveBridges;
    }

    private void registerGauges(MeterRegistry meterRegistry) {
        if (meterRegistry == null) {
            return;
        }
        Gauge.builder(SESSIONS_ACTIVE_METRIC, marketActiveBridges, AtomicInteger::get)
                .tag("upstream", UPSTREAM_MARKET)
                .description("gateway -> market WebSocket 代理当前活跃 bridge 数")
                .baseUnit("sessions")
                .register(meterRegistry);
        Gauge.builder(SESSIONS_ACTIVE_METRIC, tradingActiveBridges, AtomicInteger::get)
                .tag("upstream", UPSTREAM_TRADING)
                .description("gateway -> trading WebSocket 代理当前活跃 bridge 数")
                .baseUnit("sessions")
                .register(meterRegistry);
        this.forwardCounters = new ForwardCounters(meterRegistry);
    }

    /**
     * 4 个方向 counter（market/trading × client_to_upstream/upstream_to_client）预注册，
     * 转发热路径上零分配查表。
     */
    private static final class ForwardCounters {
        private final Counter marketClientToUpstream;
        private final Counter marketUpstreamToClient;
        private final Counter tradingClientToUpstream;
        private final Counter tradingUpstreamToClient;

        private ForwardCounters(MeterRegistry meterRegistry) {
            this.marketClientToUpstream = counter(meterRegistry, UPSTREAM_MARKET, DIRECTION_CLIENT_TO_UPSTREAM);
            this.marketUpstreamToClient = counter(meterRegistry, UPSTREAM_MARKET, DIRECTION_UPSTREAM_TO_CLIENT);
            this.tradingClientToUpstream = counter(meterRegistry, UPSTREAM_TRADING, DIRECTION_CLIENT_TO_UPSTREAM);
            this.tradingUpstreamToClient = counter(meterRegistry, UPSTREAM_TRADING, DIRECTION_UPSTREAM_TO_CLIENT);
        }

        private static Counter counter(MeterRegistry meterRegistry, String upstream, String direction) {
            return Counter.builder(MESSAGES_TOTAL_METRIC)
                    .tag("upstream", upstream)
                    .tag("direction", direction)
                    .description("gateway WebSocket 代理成功转发的帧数（TEXT/PING/PONG）")
                    .baseUnit("messages")
                    .register(meterRegistry);
        }

        private Counter counter(String upstream, String direction) {
            boolean clientToUpstream = DIRECTION_CLIENT_TO_UPSTREAM.equals(direction);
            if (UPSTREAM_TRADING.equals(upstream)) {
                return clientToUpstream ? tradingClientToUpstream : tradingUpstreamToClient;
            }
            return clientToUpstream ? marketClientToUpstream : marketUpstreamToClient;
        }
    }
}
