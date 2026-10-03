package com.falconx.wallet.service.impl;

import com.falconx.domain.enums.ChainType;
import com.falconx.wallet.config.WalletServiceProperties;
import com.falconx.wallet.entity.WalletAddressAssignment;
import com.falconx.wallet.entity.WalletAddressStatus;
import com.falconx.wallet.error.WalletBusinessException;
import com.falconx.wallet.error.WalletErrorCode;
import com.falconx.wallet.repository.WalletAddressRepository;
import com.falconx.wallet.service.WalletAddressAllocationService;
import com.falconx.wallet.service.WalletAddressDerivationService;
import com.falconx.wallet.service.model.WalletDerivedAddress;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 顺序地址分配服务。
 *
 * <p>该实现用于真实入金地址申请：
 * 先查用户在该链上是否已有地址，若已有则直接返回；
 * 若没有，则按链维度递增地址索引并通过 account-level xpub 派生真实入金地址。
 */
public class SequentialWalletAddressAllocationService implements WalletAddressAllocationService {

    private static final Logger log = LoggerFactory.getLogger(SequentialWalletAddressAllocationService.class);

    private final WalletServiceProperties properties;
    private final WalletAddressRepository walletAddressRepository;
    private final WalletAddressDerivationService walletAddressDerivationService;

    public SequentialWalletAddressAllocationService(WalletServiceProperties properties,
                                                    WalletAddressRepository walletAddressRepository,
                                                    WalletAddressDerivationService walletAddressDerivationService) {
        this.properties = properties;
        this.walletAddressRepository = walletAddressRepository;
        this.walletAddressDerivationService = walletAddressDerivationService;
    }

    @Override
    public WalletAddressAssignment allocateAddress(long userId, ChainType chain) {
        WalletAddressAssignment existing = walletAddressRepository.findByUserAndChainForUpdate(userId, chain).orElse(null);
        if (existing != null) {
            return verifiedExistingAssignment(userId, chain, existing);
        }
        return createNewAssignment(userId, chain);
    }

    private WalletAddressAssignment verifiedExistingAssignment(long userId,
                                                               ChainType chain,
                                                               WalletAddressAssignment existing) {
        if (existing.derivationPath() == null || existing.derivationPath().startsWith("legacy:")) {
            throw new WalletBusinessException(
                    WalletErrorCode.WALLET_ADDRESS_ALLOCATION_FAILED,
                    Map.of("userId", userId, "chain", chain.name(), "reason", "legacy_address_not_returnable")
            );
        }
        return existing;
    }

    private WalletAddressAssignment createNewAssignment(long userId, ChainType chain) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            int nextIndex = walletAddressRepository.nextAddressIndex(chain, properties.getAllocation().getStartIndex());
            WalletDerivedAddress derivedAddress = walletAddressDerivationService.deriveAddress(chain, nextIndex);
            WalletAddressAssignment assignment = new WalletAddressAssignment(
                    null,
                    userId,
                    chain,
                    derivedAddress.token(),
                    derivedAddress.network(),
                    derivedAddress.address(),
                    nextIndex,
                    derivedAddress.derivationPath(),
                    WalletAddressStatus.ASSIGNED,
                    OffsetDateTime.now()
            );
            try {
                return walletAddressRepository.save(assignment);
            } catch (DuplicateKeyException exception) {
                WalletAddressAssignment existing = walletAddressRepository.findByUserAndChainForUpdate(userId, chain).orElse(null);
                if (existing != null) {
                    return verifiedExistingAssignment(userId, chain, existing);
                }
                log.warn("wallet.address.allocate.retry userId={} chain={} attempt={} reason=duplicate_key",
                        userId,
                        chain,
                        attempt);
                if (attempt == 3) {
                    throw exception;
                }
            }
        }
        throw new IllegalStateException("Unable to allocate wallet address after retries");
    }
}
