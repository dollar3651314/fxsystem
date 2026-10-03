package com.falconx.console.reconciliation;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * STAGE-11-OBS-RECON §11.4：trading-core admin recon RPC 响应的本地反序列化模型。
 *
 * <p>与 trading-core {@code AdminTradingDepositReconListResponse} / {@code AdminTradingDepositReconItem}
 * 字段对齐；本地复制以避免 console-service 引入 trading-core 模块依赖
 * （AGENTS.md §3 不允许跨服务直接 import）。
 */
public final class TradingDepositReconView {

    private TradingDepositReconView() {}

    public record ListResponse(int page, int pageSize, int returned, List<Item> items) {
    }

    public record Item(
            String id,
            String walletTxId,
            String userId,
            String chain,
            String token,
            String txHash,
            BigDecimal amount,
            String status,
            OffsetDateTime creditedAt,
            OffsetDateTime reversedAt
    ) {
    }
}
