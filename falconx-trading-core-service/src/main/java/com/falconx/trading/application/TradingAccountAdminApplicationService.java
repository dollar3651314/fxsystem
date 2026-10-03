package com.falconx.trading.application;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.api.AdminBalanceAdjustResponse;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingLedgerBizType;
import com.falconx.trading.entity.TradingLedgerEntry;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.TradingAccountRepository;
import com.falconx.trading.repository.TradingLedgerRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员调整客户余额的内部 RPC 编排（STAGE-2-CUSTOMER）。
 *
 * <p>写路径同事务：
 *
 * <ol>
 *   <li>{@code findByUserIdAndCurrencyForUpdate} 加悲观锁查账户</li>
 *   <li>校验扣减后余额 ≥ 0（错误码 30031）</li>
 *   <li>{@code save} 更新 {@code t_account.balance}</li>
 *   <li>写 {@code t_ledger}（biz_type=ADMIN_BALANCE_ADJUST，amount=deltaUSD 含正负，referenceNo=traceId）</li>
 * </ol>
 *
 * <p>console 已在调用前完成限额校验（单次 ≤ $5000，单日累计 ≤ $20000，按管理端架构 §4.4 双写设计）；
 * trading-core 仅做最终余额合法性校验和持久化。
 */
@Service
public class TradingAccountAdminApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TradingAccountAdminApplicationService.class);

    private final TradingAccountRepository accountRepository;
    private final TradingLedgerRepository ledgerRepository;
    private final TradingCoreServiceProperties properties;
    private final IdGenerator idGenerator;

    public TradingAccountAdminApplicationService(TradingAccountRepository accountRepository,
                                                 TradingLedgerRepository ledgerRepository,
                                                 TradingCoreServiceProperties properties,
                                                 IdGenerator idGenerator) {
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.properties = properties;
        this.idGenerator = idGenerator;
    }

    @Transactional
    public AdminBalanceAdjustResponse adjustBalance(long userId, long adminUserId,
                                                    BigDecimal deltaUSD, String reason,
                                                    String referenceNo) {
        String currency = properties.getSettlementToken();
        TradingAccount account = accountRepository.findByUserIdAndCurrencyForUpdate(userId, currency)
                .orElseThrow(() -> new TradingBusinessException(TradingErrorCode.ACCOUNT_NOT_FOUND));

        BigDecimal balanceBefore = account.balance().setScale(8, RoundingMode.DOWN);
        BigDecimal scaledDelta = deltaUSD.setScale(8, RoundingMode.DOWN);
        BigDecimal balanceAfter = balanceBefore.add(scaledDelta);
        if (balanceAfter.signum() < 0) {
            throw new TradingBusinessException(TradingErrorCode.BALANCE_INSUFFICIENT);
        }

        OffsetDateTime now = OffsetDateTime.now();
        TradingAccount updated = new TradingAccount(
                account.accountId(),
                account.userId(),
                account.currency(),
                balanceAfter,
                account.frozen(),
                account.marginUsed(),
                account.marginMode(),
                account.modeChangedAt(),
                account.modeCoolingUntil(),
                account.createdAt(),
                now
        );
        accountRepository.save(updated);

        long ledgerId = idGenerator.nextId();
        TradingLedgerEntry ledger = new TradingLedgerEntry(
                ledgerId,
                account.accountId(),
                userId,
                TradingLedgerBizType.ADMIN_BALANCE_ADJUST,
                scaledDelta,
                scaledDelta,
                account.currency(),
                BigDecimal.ONE,
                "admin-adjust:" + referenceNo,
                referenceNo,
                balanceBefore,
                balanceAfter,
                account.frozen(),
                account.frozen(),
                account.marginUsed(),
                account.marginUsed(),
                now
        );
        ledgerRepository.save(ledger);

        log.info("trading.admin.balance-adjust.completed userId={} adminUserId={} delta={} balanceBefore={} balanceAfter={} ledgerId={} reasonLength={}",
                userId, adminUserId, scaledDelta, balanceBefore, balanceAfter, ledgerId,
                reason == null ? 0 : reason.length());

        return new AdminBalanceAdjustResponse(userId, scaledDelta, balanceBefore, balanceAfter, ledgerId);
    }
}
