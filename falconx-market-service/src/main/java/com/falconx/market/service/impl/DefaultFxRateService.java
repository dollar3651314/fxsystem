package com.falconx.market.service.impl;

import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.contract.FxRateSnapshotPayload;
import com.falconx.market.service.FxRateConverter;
import com.falconx.market.service.FxRateRedisCache;
import com.falconx.market.service.FxRateService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * FX 实时汇率领域服务默认实现。
 *
 * <p>通过 {@link ConcurrentHashMap} 维护内存中的最新快照（供交叉汇率计算与 stale 检测），
 * 同时把每次 tick 写入 Redis（TTL 可配），保证进程重启后热数据可恢复。
 *
 * <p>线程安全说明：{@code latestByPair} 使用 {@link ConcurrentHashMap}，
 * {@code acceptTick} 写入与 {@code snapshotAll} / {@code isStale} 读取均无锁竞争；
 * 若需要严格原子更新多字段快照，后续可升级为 {@code synchronized} 或读写锁。
 */
@Service
public class DefaultFxRateService implements FxRateService {

    private static final Logger log = LoggerFactory.getLogger(DefaultFxRateService.class);

    /** Redis key 前缀：{@code falconx:fx:rate:{base}:{quote}} */
    private static final String KEY_PREFIX = "falconx:fx:rate:";

    private final MarketServiceProperties props;
    private final FxRateRedisCache redis;
    private final Clock clock;

    /** 内存快照，key = "{base}/{quote}"，用于交叉换算与 stale 检测 */
    private final ConcurrentHashMap<String, FxRateSnapshotPayload> latestByPair = new ConcurrentHashMap<>();

    public DefaultFxRateService(MarketServiceProperties props, FxRateRedisCache redis, Clock clock) {
        this.props = props;
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * 接收 LP 报来的 FX tick：更新内存快照并写入 Redis（TTL = {@code falconx.market.fx.redisTtlSeconds}）。
     */
    @Override
    public void acceptTick(FxRateSnapshotPayload payload) {
        String redisKey = KEY_PREFIX + payload.baseCurrency() + ":" + payload.quoteCurrency();
        redis.set(redisKey, payload.rate().toPlainString(), props.getFx().getRedisTtlSeconds());
        latestByPair.put(payload.baseCurrency() + "/" + payload.quoteCurrency(), payload);
        log.debug("fx.rate.accepted base={} quote={} rate={} ts={}",
                payload.baseCurrency(), payload.quoteCurrency(),
                payload.rate(), payload.eventTimeMillis());
    }

    /**
     * 查询 {@code from} → {@code to} 汇率（含同币种、直接对、反向对、USD 交叉）。
     *
     * <p>基于内存快照构建 {@link FxRateConverter} 实时计算，不走 Redis（热路径低延迟）。
     *
     * @return 包含汇率的 Optional，对不可用时返回 empty
     */
    @Override
    public Optional<BigDecimal> queryRate(String from, String to) {
        Map<String, BigDecimal> snapshot = new HashMap<>();
        latestByPair.forEach((k, v) -> snapshot.put(k, v.rate()));
        FxRateConverter converter = new FxRateConverter(snapshot);
        return Optional.ofNullable(converter.rate(from, to));
    }

    /**
     * 返回所有已知货币对的最新快照（不可变副本），供 internal RPC 使用。
     */
    @Override
    public List<FxRateSnapshotPayload> snapshotAll() {
        return List.copyOf(latestByPair.values());
    }

    /**
     * 检查 {@code base}/{@code quote} 是否 stale：
     * 若从未收到过 tick，或最后 tick 距今超过 {@code staleThresholdSeconds}，则视为 stale。
     */
    @Override
    public boolean isStale(String base, String quote) {
        FxRateSnapshotPayload p = latestByPair.get(base + "/" + quote);
        if (p == null) {
            return true;
        }
        long ageSec = (clock.millis() - p.eventTimeMillis()) / 1000;
        return ageSec > props.getFx().getStaleThresholdSeconds();
    }
}
