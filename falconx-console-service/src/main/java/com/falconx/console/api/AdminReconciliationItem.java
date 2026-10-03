package com.falconx.console.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * STAGE-11-OBS-RECON §14.1：入金对账 unmatched 项 DTO（透传给前端）。
 *
 * <p>雪花 ID（walletTxId / tradingDepositId / userId）以 String 表示防 JS 精度丢失。
 *
 * <p>discrepancyType 枚举（与 console-pages-V1 §17.1 一致）：
 * <ul>
 *   <li>{@code WALLET_ONLY}：wallet confirmed 但 trading-core 无对应记录</li>
 *   <li>{@code TRADING_ONLY}：trading-core credited 但 wallet 无对应 confirmed（罕见）</li>
 *   <li>{@code AMOUNT_MISMATCH}：两端按 (chain, txHash) 匹配但 amount 不一致</li>
 *   <li>{@code STATUS_DIVERGED}：wallet REVERSED 但 trading-core 仍 CREDITED（或反之）</li>
 * </ul>
 */
public record AdminReconciliationItem(
        String walletTxId,
        String tradingDepositId,
        String chain,
        String token,
        String txHash,
        BigDecimal walletAmount,
        BigDecimal tradingAmount,
        String walletStatus,
        String tradingStatus,
        String discrepancyType,
        OffsetDateTime walletDetectedAt,
        OffsetDateTime walletConfirmedAt,
        String userId,
        String toAddress,
        /** 跨 schema enrich：用户对外短号（t_user.uid）。null = 未 enrich / userId 缺失 / 用户不存在。 */
        String userUid,
        /** 跨 schema enrich：用户邮箱。null = 未 enrich。 */
        String userEmail,
        /** 跨 schema enrich：用户姓名（firstName + lastName，profile 未填为 null）。 */
        String userFullName
) {
    /** Builder-style helper：基于已有 item 覆写 3 个用户信息字段，主字段不变。 */
    public AdminReconciliationItem withUserInfo(String userUid, String userEmail, String userFullName) {
        return new AdminReconciliationItem(walletTxId, tradingDepositId, chain, token, txHash,
                walletAmount, tradingAmount, walletStatus, tradingStatus, discrepancyType,
                walletDetectedAt, walletConfirmedAt, userId, toAddress,
                userUid, userEmail, userFullName);
    }
}
