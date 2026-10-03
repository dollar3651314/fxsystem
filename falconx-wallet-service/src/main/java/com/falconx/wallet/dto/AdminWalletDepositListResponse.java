package com.falconx.wallet.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * STAGE-2-DEPOSIT R4：管理端入金记录列表响应（trading-core/wallet internal RPC）。
 */
public record AdminWalletDepositListResponse(List<Item> items, long total, int page, int size) {

    public record Item(
            Long id,
            Long userId,
            String chain,
            String token,
            String tokenContractAddress,
            String txHash,
            Integer logIndex,
            String fromAddress,
            String toAddress,
            BigDecimal amount,
            Long blockNumber,
            Integer confirmations,
            Integer requiredConfirms,
            String status,
            LocalDateTime detectedAt,
            LocalDateTime confirmedAt,
            LocalDateTime updatedAt
    ) {
    }
}
