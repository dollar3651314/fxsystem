package com.falconx.wallet.entity;

import com.falconx.domain.enums.ChainType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 钱包原始入金交易实体。
 *
 * <p>该对象对应 `falconx_wallet.t_wallet_deposit_tx` 的领域表达。
 * 它用于冻结链上原始事实的字段形态、状态推进逻辑和事件发布边界。
 *
 * @param id 主键 ID，由 owner 仓储在持久化时补齐
 * @param userId 归属用户 ID，若地址不归平台所有则为空
 * @param chain 链类型
 * @param token 代币符号
 * @param tokenContractAddress 代币合约地址；原生币为空
 * @param txHash 链上交易哈希
 * @param logIndex 同一交易中的 transfer 序号；原生币固定为 0
 * @param fromAddress 来源地址
 * @param toAddress 目标地址
 * @param amount 已按 token decimals 归一化后的业务金额，统一保留 8 位小数
 * @param blockNumber 区块高度
 * @param confirmations 当前确认数
 * @param requiredConfirmations 所需确认数
 * @param status 当前状态
 * @param detectedAt 首次检测时间
 * @param confirmedAt 最终确认时间
 * @param updatedAt 最后更新时间
 */
public record WalletDepositTransaction(
        Long id,
        Long userId,
        ChainType chain,
        String token,
        String tokenContractAddress,
        String txHash,
        int logIndex,
        String fromAddress,
        String toAddress,
        BigDecimal amount,
        Long blockNumber,
        int confirmations,
        int requiredConfirmations,
        WalletDepositStatus status,
        OffsetDateTime detectedAt,
        OffsetDateTime confirmedAt,
        OffsetDateTime updatedAt
) {
}
