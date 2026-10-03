package com.falconx.wallet.listener;

import com.falconx.domain.enums.ChainType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 链监听器输出的原始入金观察结果。
 *
 * <p>该对象是 Stage 2B 在 listener 与 application 之间传递的统一输入模型。
 * 真实链节点客户端接入后，只允许先转换成该对象，再进入 wallet 应用层。
 *
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
 * @param observedAt 本次监听观察时间
 * @param reversed 是否被判定为回滚事件
 */
public record ObservedDepositTransaction(
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
        OffsetDateTime observedAt,
        boolean reversed
) {
}
