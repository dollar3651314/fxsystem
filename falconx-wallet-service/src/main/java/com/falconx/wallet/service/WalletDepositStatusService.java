package com.falconx.wallet.service;

import com.falconx.wallet.entity.WalletDepositStatus;
import com.falconx.wallet.entity.WalletDepositTransaction;
import com.falconx.wallet.listener.ObservedDepositTransaction;
import java.util.Optional;

/**
 * 钱包原始入金状态判定服务抽象。
 *
 * <p>该服务把链上观察结果转换成 wallet owner 的状态机结果，
 * 避免应用层直接散落确认数、回滚和地址归属的判断细节。
 */
public interface WalletDepositStatusService {

    /**
     * 根据观察结果和地址归属情况判定原始入金状态。
     *
     * @param observedDeposit 链监听观察结果
     * @param existingTransaction 当前已落库的原始入金记录，用于约束状态机不倒退
     * @param assignedAddressFound 是否找到平台地址归属
     * @return 目标钱包入金状态
     */
    WalletDepositStatus determineStatus(ObservedDepositTransaction observedDeposit,
                                        Optional<WalletDepositTransaction> existingTransaction,
                                        boolean assignedAddressFound);
}
