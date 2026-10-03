package com.falconx.wallet.application;

import com.falconx.domain.enums.ChainType;
import com.falconx.wallet.dto.AdminWalletDepositListResponse;
import com.falconx.wallet.entity.WalletDepositStatus;
import com.falconx.wallet.entity.WalletDepositTransaction;
import com.falconx.wallet.error.WalletBusinessException;
import com.falconx.wallet.error.WalletErrorCode;
import com.falconx.wallet.repository.WalletDepositTransactionRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * STAGE-2-DEPOSIT R4：管理端入金记录只读编排。
 *
 * <p>纯只读，不跨 schema；按 R2 一轮决策只返回 t_wallet_deposit_tx 字段。
 */
@Service
public class WalletDepositAdminApplicationService {

    private static final Logger log = LoggerFactory.getLogger(WalletDepositAdminApplicationService.class);

    private final WalletDepositTransactionRepository repository;

    public WalletDepositAdminApplicationService(WalletDepositTransactionRepository repository) {
        this.repository = repository;
    }

    public AdminWalletDepositListResponse listDeposits(Long userId, String chain, String token,
                                                       String statusCsv,
                                                       OffsetDateTime fromDetectedAt,
                                                       OffsetDateTime toDetectedAt,
                                                       boolean onlyOrphan,
                                                       int page, int size) {
        int offset = (page - 1) * size;
        ChainType chainType = parseChain(chain);
        List<WalletDepositStatus> statuses = parseStatuses(statusCsv);
        List<WalletDepositTransaction> items = repository.findAdminPaginated(
                userId, chainType, token, statuses, fromDetectedAt, toDetectedAt, onlyOrphan, offset, size);
        long total = repository.countAdminFiltered(userId, chainType, token, statuses, fromDetectedAt, toDetectedAt, onlyOrphan);
        List<AdminWalletDepositListResponse.Item> dtoItems = items.stream().map(this::toItem).toList();
        return new AdminWalletDepositListResponse(dtoItems, total, page, size);
    }

    public AdminWalletDepositListResponse.Item getDepositById(long id) {
        return repository.findById(id)
                .map(this::toItem)
                .orElseThrow(() -> new WalletBusinessException(
                        WalletErrorCode.ADMIN_DEPOSIT_NOT_FOUND, Map.of("id", id)));
    }

    /**
     * STAGE-6-KYC trigger 2：返回用户在指定链上历史 CONFIRMED 入金的 from_address 去重列表。
     * 用于 trading-core 出金前置陌生地址校验。chain 解析失败时返回空列表。
     */
    public List<String> listConfirmedFromAddresses(long userId, String chain) {
        ChainType chainType = parseChain(chain);
        if (chainType == null) {
            return List.of();
        }
        return repository.findDistinctFromAddressesByUserAndChain(userId, chainType);
    }

    private ChainType parseChain(String chain) {
        if (chain == null || chain.isBlank()) {
            return null;
        }
        try {
            return ChainType.valueOf(chain.toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("wallet.admin.deposit.unknown-chain chain={}", chain);
            return null; // 返回 null → mapper 不带条件，列表为空，与契约一致
        }
    }

    private List<WalletDepositStatus> parseStatuses(String statusCsv) {
        if (statusCsv == null || statusCsv.isBlank()) {
            return List.of();
        }
        List<WalletDepositStatus> out = new ArrayList<>();
        for (String token : statusCsv.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            try {
                out.add(WalletDepositStatus.valueOf(trimmed.toUpperCase()));
            } catch (IllegalArgumentException ex) {
                log.warn("wallet.admin.deposit.unknown-status status={}", trimmed);
            }
        }
        return out;
    }

    private AdminWalletDepositListResponse.Item toItem(WalletDepositTransaction tx) {
        return new AdminWalletDepositListResponse.Item(
                tx.id(),
                tx.userId(),
                tx.chain() == null ? null : tx.chain().name(),
                tx.token(),
                tx.tokenContractAddress(),
                tx.txHash(),
                tx.logIndex(),
                tx.fromAddress(),
                tx.toAddress(),
                tx.amount(),
                tx.blockNumber(),
                tx.confirmations(),
                tx.requiredConfirmations(),
                tx.status() == null ? null : tx.status().name(),
                tx.detectedAt() == null ? null : tx.detectedAt().toLocalDateTime(),
                tx.confirmedAt() == null ? null : tx.confirmedAt().toLocalDateTime(),
                tx.updatedAt() == null ? null : tx.updatedAt().toLocalDateTime()
        );
    }
}
