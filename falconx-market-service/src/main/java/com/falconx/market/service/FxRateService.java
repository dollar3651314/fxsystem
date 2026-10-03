package com.falconx.market.service;

import com.falconx.market.contract.FxRateSnapshotPayload;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * FX 实时汇率领域服务。
 *
 * <p>提供 FX 报价接收、实时汇率查询（含交叉对）、全量快照与 stale 检测四个能力。
 */
public interface FxRateService {

    /** 接收新 FX 报价（由 LP 行情监听调用） */
    void acceptTick(FxRateSnapshotPayload payload);

    /** 查询 from→to 实时 rate（含交叉），null 表示不可用 */
    Optional<BigDecimal> queryRate(String from, String to);

    /** 列出所有 base/quote 当前已知 rate 快照（供 internal RPC） */
    List<FxRateSnapshotPayload> snapshotAll();

    /** 检查某个 base/quote 是否 stale（用于 StaleDetector）  */
    boolean isStale(String base, String quote);
}
