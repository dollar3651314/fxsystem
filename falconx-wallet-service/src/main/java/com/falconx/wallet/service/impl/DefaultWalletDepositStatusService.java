package com.falconx.wallet.service.impl;

import com.falconx.wallet.config.WalletServiceProperties;
import com.falconx.wallet.entity.WalletDepositStatus;
import com.falconx.wallet.entity.WalletDepositTransaction;
import com.falconx.wallet.listener.ObservedDepositTransaction;
import com.falconx.wallet.service.WalletDepositStatusService;
import java.util.Optional;

/**
 * 钱包入金状态判定默认实现。
 *
 * <p>该实现完全遵循状态机规范：
 * 若地址不归平台，则标记为 IGNORED；
 * 若链回滚且当前记录已 CONFIRMED，则标记为 REVERSED；
 * 若链回滚发生在 CONFIRMED 之前，则保持原状态或按首次无效观察记为 IGNORED；
 * 若确认数达到阈值，则标记为 CONFIRMED；
 * 若确认数大于零但未达阈值，则标记为 CONFIRMING；
 * 否则标记为 DETECTED。
 */
public class DefaultWalletDepositStatusService implements WalletDepositStatusService {

    private final WalletServiceProperties properties;

    public DefaultWalletDepositStatusService(WalletServiceProperties properties) {
        this.properties = properties;
    }

    @Override
    public WalletDepositStatus determineStatus(ObservedDepositTransaction observedDeposit,
                                               Optional<WalletDepositTransaction> existingTransaction,
                                               boolean assignedAddressFound) {
        if (!assignedAddressFound) {
            return WalletDepositStatus.IGNORED;
        }
        WalletDepositStatus currentStatus = existingTransaction.map(WalletDepositTransaction::status).orElse(null);
        if (currentStatus == WalletDepositStatus.REVERSED || currentStatus == WalletDepositStatus.IGNORED) {
            return currentStatus;
        }
        if (observedDeposit.reversed()) {
            // 状态机只允许 `CONFIRMED -> REVERSED`。
            // 因此预确认阶段出现的回滚观察不能直接推进到 REVERSED：
            // - 已存在记录时保持原状态，等待后续更稳定的链事实
            // - 首次观察即回滚时，按无效链事实处理为 IGNORED，且不对外发 reversal 事件
            if (currentStatus == WalletDepositStatus.CONFIRMED) {
                return WalletDepositStatus.REVERSED;
            }
            if (currentStatus != null) {
                return currentStatus;
            }
            return WalletDepositStatus.IGNORED;
        }
        int requiredConfirmations = properties.chain(observedDeposit.chain()).getRequiredConfirmations();
        if (observedDeposit.confirmations() >= requiredConfirmations) {
            return WalletDepositStatus.CONFIRMED;
        }
        if (observedDeposit.confirmations() > 0) {
            return WalletDepositStatus.CONFIRMING;
        }
        return WalletDepositStatus.DETECTED;
    }
}
