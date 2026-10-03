package com.falconx.trading.service.impl;

import com.falconx.common.api.ApiResponse;
import com.falconx.market.contract.FxRateSnapshotPayload;
import com.falconx.trading.client.TradingExternalRpcClient;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.service.FxRateConverterHelper;
import com.falconx.trading.service.FxRateService;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * STAGE-14B Task 3：trading-core 内存 FX 汇率服务实现。
 *
 * <p>启动时（{@link PostConstruct}）通过 RPC 拉取 market-service 全量 FX 快照写入内存；
 * 运行时由 {@code FxRateUpdateEventConsumer} 调 {@link #acceptUpdate} 持续刷新。
 *
 * <p>bootstrap 失败仅 warn（{@code bootstrap-failure-policy=warn}），不阻断服务启动；
 * 内存初始为空，等 Kafka 增量补充。
 *
 * <p>线程安全：内部 {@link ConcurrentHashMap} 对 {@code acceptUpdate} 的并发写是 O(1) 非阻塞，
 * 可在 Kafka 回调线程直接调用（参考既有 {@code MarketPriceTickEventConsumer} 模式）。
 */
@Service
public class DefaultFxRateService implements FxRateService {

    private static final Logger log = LoggerFactory.getLogger(DefaultFxRateService.class);

    private static final String FX_RATES_PATH = "/internal/v1/market/fx/rates";

    private static final ParameterizedTypeReference<ApiResponse<List<FxRateSnapshotPayload>>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final ConcurrentHashMap<String, BigDecimal> latestByPair = new ConcurrentHashMap<>();
    private final TradingExternalRpcClient rpcClient;
    private final TradingCoreServiceProperties props;

    public DefaultFxRateService(TradingExternalRpcClient rpcClient,
                                TradingCoreServiceProperties props) {
        this.rpcClient = rpcClient;
        this.props = props;
    }

    /**
     * 启动时 RPC 拉取 market-service 全量 FX 快照。
     *
     * <p>失败仅 warn 不抛出（bootstrap-failure-policy=warn），服务继续启动，等 Kafka 增量补充。
     */
    @PostConstruct
    public void bootstrap() {
        try {
            List<FxRateSnapshotPayload> all = rpcClient.get(FX_RATES_PATH, RESPONSE_TYPE);
            if (all == null) {
                log.warn("trading.fx.bootstrap.empty — market returned null list, 等 Kafka 增量补充");
                return;
            }
            all.forEach(p -> acceptUpdate(p.baseCurrency(), p.quoteCurrency(), p.rate(), p.eventTimeMillis()));
            log.info("trading.fx.bootstrap.ok size={}", all.size());
        } catch (Exception ex) {
            log.warn("trading.fx.bootstrap.failed reason={} — 等 Kafka 增量补充", ex.getMessage(), ex);
        }
    }

    @Override
    public Optional<BigDecimal> queryRate(String from, String to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.equals(to)) return Optional.of(BigDecimal.ONE);
        return FxRateConverterHelper.compute(latestByPair, from, to);
    }

    @Override
    public void acceptUpdate(String base, String quote, BigDecimal rate, long eventTimeMillis) {
        if (base == null || quote == null || rate == null) {
            log.warn("trading.fx.accept-update.invalid base={} quote={} rate={}", base, quote, rate);
            return;
        }
        latestByPair.put(base + "/" + quote, rate);
    }

    @Override
    public Map<String, BigDecimal> snapshotAll() {
        return Map.copyOf(latestByPair);
    }
}
