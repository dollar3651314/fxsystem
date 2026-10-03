package com.falconx.wallet.application;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.wallet.entity.WalletWithdrawWhitelist;
import com.falconx.wallet.entity.WalletWithdrawWhitelistStatus;
import com.falconx.wallet.error.WalletBusinessException;
import com.falconx.wallet.error.WalletErrorCode;
import com.falconx.wallet.repository.WalletWithdrawWhitelistRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-7-WITHDRAW Phase 2：wallet-service 白名单业务应用服务。
 *
 * <p>对外通过 {@link com.falconx.wallet.controller.AdminInternalWalletWithdrawController} 暴露。
 * 业务规则：
 * <ul>
 *   <li>每用户最多 10 条 ACTIVE（含 PENDING）→ 超限 {@link WalletErrorCode#WITHDRAW_WHITELIST_LIMIT_EXCEEDED}</li>
 *   <li>同 user_id + network + address 在 PENDING/ACTIVE 中已存在 → {@link WalletErrorCode#WITHDRAW_WHITELIST_DUPLICATE}</li>
 *   <li>新增成功后 status=PENDING（24h 冷静期由调度器或惰性检查切到 ACTIVE）</li>
 *   <li>删除按 status=ACTIVE/PENDING → REMOVED（保留历史）；REMOVED 后允许同地址再次添加</li>
 * </ul>
 */
@Service
public class WalletWithdrawWhitelistApplicationService {

    private static final Logger log = LoggerFactory.getLogger(WalletWithdrawWhitelistApplicationService.class);

    private static final int MAX_ACTIVE_WHITELIST_PER_USER = 10;

    private final WalletWithdrawWhitelistRepository whitelistRepository;
    private final IdGenerator idGenerator;

    public WalletWithdrawWhitelistApplicationService(WalletWithdrawWhitelistRepository whitelistRepository,
                                                      IdGenerator idGenerator) {
        this.whitelistRepository = whitelistRepository;
        this.idGenerator = idGenerator;
    }

    /**
     * 新增白名单。
     *
     * @param userId 用户 ID
     * @param network ERC20 / TRC20
     * @param address 链上地址（调用方已做格式校验）
     * @param label 可选备注（≤64）
     * @return 新建的白名单（status=PENDING）
     */
    @Transactional
    public WalletWithdrawWhitelist add(long userId, String network, String address, String label) {
        // 上限校验：ACTIVE + PENDING 合计 ≤ 10
        List<WalletWithdrawWhitelist> active = whitelistRepository.findActiveByUserId(userId);
        if (active.size() >= MAX_ACTIVE_WHITELIST_PER_USER) {
            throw new WalletBusinessException(WalletErrorCode.WITHDRAW_WHITELIST_LIMIT_EXCEEDED,
                    Map.of("userId", userId, "current", active.size(), "max", MAX_ACTIVE_WHITELIST_PER_USER));
        }
        // 重复校验：同 user_id + network + address + (PENDING/ACTIVE) 已存在
        Optional<WalletWithdrawWhitelist> dup = whitelistRepository.findByUserNetworkAddressActive(userId, network, address);
        if (dup.isPresent()) {
            throw new WalletBusinessException(WalletErrorCode.WITHDRAW_WHITELIST_DUPLICATE,
                    Map.of("userId", userId, "network", network));
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long id = idGenerator.nextId();
        WalletWithdrawWhitelist whitelist = new WalletWithdrawWhitelist(
                id, userId, network, address,
                normalizeLabel(label),
                WalletWithdrawWhitelistStatus.PENDING,
                null, null, now, now
        );
        whitelistRepository.insert(whitelist);
        log.info("wallet.withdraw.whitelist.added userId={} id={} network={}", userId, id, network);
        return whitelist;
    }

    /**
     * 列出用户可见的白名单（PENDING + ACTIVE）。
     */
    @Transactional(readOnly = true)
    public List<WalletWithdrawWhitelist> list(long userId) {
        return whitelistRepository.findActiveByUserId(userId);
    }

    /**
     * 删除白名单（status=ACTIVE/PENDING → REMOVED）。
     *
     * @return 删除后的对象（已变为 REMOVED）
     * @throws WalletBusinessException 20020 不存在或非己
     */
    @Transactional
    public WalletWithdrawWhitelist delete(long userId, long whitelistId) {
        WalletWithdrawWhitelist existing = whitelistRepository.findById(whitelistId)
                .filter(w -> w.userId() == userId)
                .filter(w -> w.status() != WalletWithdrawWhitelistStatus.REMOVED)
                .orElseThrow(() -> new WalletBusinessException(WalletErrorCode.WITHDRAW_WHITELIST_NOT_FOUND,
                        Map.of("id", whitelistId)));

        whitelistRepository.markRemoved(whitelistId);
        log.info("wallet.withdraw.whitelist.removed userId={} id={}", userId, whitelistId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new WalletWithdrawWhitelist(
                existing.id(), existing.userId(), existing.network(), existing.address(),
                existing.label(), WalletWithdrawWhitelistStatus.REMOVED,
                existing.activatedAt(), now, existing.createdAt(), now
        );
    }

    private static String normalizeLabel(String label) {
        if (label == null) return null;
        String trimmed = label.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }
}
