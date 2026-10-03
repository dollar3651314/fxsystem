package com.falconx.wallet.application;

import com.falconx.wallet.entity.WalletAddressAssignment;
import com.falconx.wallet.entity.WalletAddressProvisionDlqEntry;
import com.falconx.wallet.entity.WalletAddressProvisionDlqStatus;
import com.falconx.wallet.error.WalletBusinessException;
import com.falconx.wallet.error.WalletErrorCode;
import com.falconx.wallet.repository.WalletAddressProvisionDlqRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-5-WALLET-PROVISION Phase 2：地址预分配 DLQ 管理端应用服务。
 */
@Service
public class WalletAddressProvisionAdminApplicationService {

    private static final Logger log = LoggerFactory.getLogger(WalletAddressProvisionAdminApplicationService.class);

    private final WalletAddressProvisionDlqRepository dlqRepository;
    private final WalletAddressAllocationApplicationService allocationService;

    public WalletAddressProvisionAdminApplicationService(WalletAddressProvisionDlqRepository dlqRepository,
                                                          WalletAddressAllocationApplicationService allocationService) {
        this.dlqRepository = dlqRepository;
        this.allocationService = allocationService;
    }

    public List<WalletAddressProvisionDlqEntry> list(Integer statusCode, Long userId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return dlqRepository.findPaginated(statusCode, userId, (safePage - 1) * safeSize, safeSize);
    }

    public long count(Integer statusCode, Long userId) {
        return dlqRepository.countFiltered(statusCode, userId);
    }

    /**
     * 手动重试某条 DLQ：调 ensureDefaultUsdtDepositAddresses，成功后置为 RESOLVED。
     */
    @Transactional
    public WalletAddressProvisionDlqEntry retry(long id, long adminUserId, String reason) {
        WalletAddressProvisionDlqEntry entry = dlqRepository.findById(id)
                .orElseThrow(() -> new WalletBusinessException(WalletErrorCode.WALLET_ADDRESS_DLQ_NOT_FOUND));
        if (entry.status() == WalletAddressProvisionDlqStatus.RESOLVED) {
            throw new WalletBusinessException(WalletErrorCode.WALLET_ADDRESS_DLQ_ALREADY_RESOLVED);
        }
        log.info("wallet.admin.provision-dlq.retry.received id={} userId={} adminUserId={} reason={}",
                id, entry.userId(), adminUserId, reason);
        List<WalletAddressAssignment> assignments = allocationService.ensureDefaultUsdtDepositAddresses(entry.userId());
        dlqRepository.markResolved(id);
        log.info("wallet.admin.provision-dlq.retry.completed id={} userId={} addressCount={}",
                id, entry.userId(), assignments.size());
        return dlqRepository.findById(id).orElse(entry);
    }
}
