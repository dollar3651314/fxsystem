package com.falconx.console.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * STAGE-2-DEPOSIT：管理端入金记录列表响应。
 */
public record AdminDepositListResponse(List<Item> items, long total, int page, int size) {

    public record Item(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long userId,
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
            LocalDateTime updatedAt,
            // V23 起：从 falconx_trading.t_deposit 拼回 trading-core 入账状态
            // creditStatus 枚举：CREDITED / REJECTED / REVERSED / NOT_FOUND（仅扫块未到账或孤儿）
            String creditStatus,
            String rejectionReason,
            /** 跨 schema enrich：用户对外短号（t_user.uid）。null = 未 enrich / 用户不存在。 */
            String userUid,
            /** 跨 schema enrich：用户邮箱。null = 未 enrich。 */
            String userEmail,
            /** 跨 schema enrich：用户姓名（firstName + lastName，profile 未填为 null）。 */
            String userFullName
    ) {
        /** Wallet → console 转发时构造，初始无 enrichment（creditStatus=null）。 */
        public Item withoutCreditEnrichment() {
            return this;
        }

        /** console-service 跨 schema 查 t_deposit 后回填。 */
        public Item withCreditEnrichment(String creditStatus, String rejectionReason) {
            return new Item(id, userId, chain, token, tokenContractAddress, txHash, logIndex,
                    fromAddress, toAddress, amount, blockNumber, confirmations, requiredConfirms,
                    status, detectedAt, confirmedAt, updatedAt, creditStatus, rejectionReason,
                    userUid, userEmail, userFullName);
        }

        /** console-service 跨 schema 查 t_user 后回填用户基本信息（uid / 邮箱 / 姓名）。 */
        public Item withUserInfo(String userUid, String userEmail, String userFullName) {
            return new Item(id, userId, chain, token, tokenContractAddress, txHash, logIndex,
                    fromAddress, toAddress, amount, blockNumber, confirmations, requiredConfirms,
                    status, detectedAt, confirmedAt, updatedAt, creditStatus, rejectionReason,
                    userUid, userEmail, userFullName);
        }
    }
}
