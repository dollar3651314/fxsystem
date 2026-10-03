package com.falconx.console.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * STAGE-7-WITHDRAW Phase 4：管理端出金审核工作台单条记录。
 *
 * <p>主字段直接透传 trading-core internal RPC 的 {@code WithdrawOrderResponse}。Admin 视图扩展字段
 * {@code kycLevel / userEmail / dailyAccumulatedUsd} 在 console 端通过跨 schema 单 SQL JOIN
 * {@code falconx_identity.t_user + falconx_trading.t_withdraw_order} 装配（commit C，
 * {@code AdminWithdrawEnrichmentRepository}），list() 走批查（一次 SQL 拿全部 userIds），
 * detail() 走单 ID 复用同一接口。enrichment 失败（DB 异常 / userId 不在 t_user）时三字段为 null。
 *
 * <p>withdrawId / userId 用 String 而非 long，原因见 [FX-071]：雪花 ID 超 JS Number.MAX_SAFE_INTEGER 会丢精度，
 * 全链路（trading-core / console / frontend）以 String 序列化保证一致。
 *
 * <p>字段名 {@code withdrawId} 与 [REST 接口规范 §9.2.2] 和 trading-core {@code WithdrawOrderResponse}
 * 对齐；Phase 4 commit 1 误用 {@code id}（Phase 4 commit 3 修正）。
 */
public record AdminWithdrawItem(
        String withdrawId,
        String userId,
        BigDecimal amount,
        String currency,
        String network,
        String targetAddress,
        String status,
        OffsetDateTime coolingUntil,
        OffsetDateTime delayedUntil,
        String rejectReason,
        String txHash,
        Integer confirmations,
        String failureReason,
        OffsetDateTime createdAt,
        /** 跨 schema enrich：用户当前 KYC 等级（0 / 1）。null = 未 enrich 或用户不存在。 */
        Integer kycLevel,
        /** 跨 schema enrich：用户邮箱。null = 未 enrich。 */
        String userEmail,
        /**
         * 跨 schema enrich：用户当日 UTC 已创建且仍计入单日上限的累计出金（USDT，与单日 $30K 上限同口径）。
         * 0 = 当日无任何累计；null = 未 enrich（DB 异常等）。
         */
        BigDecimal dailyAccumulatedUsd,
        /** 跨 schema enrich：用户对外短号（t_user.uid）。null = 未 enrich / 用户不存在。 */
        String userUid,
        /** 跨 schema enrich：用户姓名（firstName + lastName，profile 未填为 null）。 */
        String userFullName
) {
    /** Builder-style helper：基于已有 item 覆写三 enrichment 字段，其余字段保持不变。 */
    public AdminWithdrawItem withEnrichment(Integer kycLevel, String userEmail, BigDecimal dailyAccumulatedUsd) {
        return new AdminWithdrawItem(
                withdrawId, userId, amount, currency, network, targetAddress, status,
                coolingUntil, delayedUntil, rejectReason, txHash, confirmations, failureReason, createdAt,
                kycLevel, userEmail, dailyAccumulatedUsd, userUid, userFullName
        );
    }

    /** 覆写 uid + 姓名（邮箱已由 withEnrichment 填充），其余字段不变。 */
    public AdminWithdrawItem withUserName(String userUid, String userFullName) {
        return new AdminWithdrawItem(
                withdrawId, userId, amount, currency, network, targetAddress, status,
                coolingUntil, delayedUntil, rejectReason, txHash, confirmations, failureReason, createdAt,
                kycLevel, userEmail, dailyAccumulatedUsd, userUid, userFullName
        );
    }
}
