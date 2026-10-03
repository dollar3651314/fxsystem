package com.falconx.trading.application;

import com.falconx.trading.command.CreditConfirmedDepositCommand;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.contract.event.DepositCreditedEventPayload;
import com.falconx.trading.dto.DepositCreditResult;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingDeposit;
import com.falconx.trading.entity.TradingDepositStatus;
import com.falconx.trading.entity.TradingOutboxMessage;
import com.falconx.trading.entity.TradingOutboxStatus;
import com.falconx.trading.repository.TradingDepositRepository;
import com.falconx.trading.repository.TradingInboxRepository;
import com.falconx.trading.repository.TradingOutboxRepository;
import com.falconx.trading.service.TradingAccountService;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业务入金入账应用服务。
 *
 * <p>该服务是 Stage 3B 最关键的交易核心编排链路之一，用于把 `wallet.deposit.confirmed`
 * 事件转换成 trading-core-service owner 的最终业务事实：
 *
 * <ol>
 *   <li>按 `eventId` 做低频关键事件幂等</li>
 *   <li>按 `walletTxId` 做业务入金幂等</li>
 *   <li>创建或读取交易账户并入账</li>
 *   <li>写 `t_deposit` 业务事实</li>
 *   <li>写 `t_outbox`，为 `falconx.trading.deposit.credited` 做后续发布准备</li>
 * </ol>
 */
@Service
public class TradingDepositCreditApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TradingDepositCreditApplicationService.class);

    private final TradingCoreServiceProperties properties;
    private final TradingAccountService tradingAccountService;
    private final TradingDepositRepository tradingDepositRepository;
    private final TradingInboxRepository tradingInboxRepository;
    private final TradingOutboxRepository tradingOutboxRepository;
    private final TradingNotificationApplicationService notificationService;

    public TradingDepositCreditApplicationService(TradingCoreServiceProperties properties,
                                                  TradingAccountService tradingAccountService,
                                                  TradingDepositRepository tradingDepositRepository,
                                                  TradingInboxRepository tradingInboxRepository,
                                                  TradingOutboxRepository tradingOutboxRepository,
                                                  TradingNotificationApplicationService notificationService) {
        this.properties = properties;
        this.tradingAccountService = tradingAccountService;
        this.tradingDepositRepository = tradingDepositRepository;
        this.tradingInboxRepository = tradingInboxRepository;
        this.tradingOutboxRepository = tradingOutboxRepository;
        this.notificationService = notificationService;
    }

    /**
     * 执行业务入金入账。
     *
     * @param command 钱包确认入金命令
     * @return 入账结果
     */
    @Transactional
    public DepositCreditResult creditConfirmedDeposit(CreditConfirmedDepositCommand command) {
        log.info("trading.deposit.credit.request eventId={} walletTxId={} userId={} chain={} token={} txHash={}",
                command.eventId(),
                command.walletTxId(),
                command.userId(),
                command.chain(),
                command.token(),
                command.txHash());

        // STAGE-2-DEPOSIT 2026-05-19：token 白名单防护。
        // 账户按 USDT 1:1 计价；原生 ETH/BNB 或非预期 ERC20 误入若 1:1 入账会出大事
        // （1 ETH = $3000+ 但会被加成 1 USDT；反向也会给用户白送几千刀）。
        // V23 起：不在白名单的 token 写 REJECTED 行留痕（account_id=null，不动账户、不发 outbox），
        // 供 admin 审计可见 + ops 报警；不写会让 grep 日志成为唯一线索，太脆弱。
        if (!properties.isDepositTokenAllowed(command.token())) {
            tradingInboxRepository.markProcessedIfAbsent(
                    command.eventId(),
                    "wallet.deposit.confirmed",
                    command.confirmedAt()
            );
            // 幂等：同一 walletTxId 已经写过 REJECTED 行就不重复写
            TradingDeposit existingRejected = tradingDepositRepository.findByWalletTxId(command.walletTxId()).orElse(null);
            if (existingRejected != null) {
                log.info("trading.deposit.credit.rejected.duplicate walletTxId={} depositId={} status={}",
                        command.walletTxId(), existingRejected.depositId(), existingRejected.status());
                return new DepositCreditResult(existingRejected, null, true);
            }
            TradingDeposit rejected = tradingDepositRepository.save(new TradingDeposit(
                    null,
                    command.walletTxId(),
                    command.userId(),
                    null,
                    command.chain(),
                    command.token(),
                    command.txHash(),
                    command.amount(),
                    TradingDepositStatus.REJECTED,
                    null,
                    null,
                    "token_not_whitelisted",
                    command.confirmedAt()
            ));
            log.warn("trading.deposit.credit.rejected reason=token_not_whitelisted eventId={} walletTxId={} depositId={} userId={} chain={} token={} amount={} txHash={} whitelist={}",
                    command.eventId(),
                    command.walletTxId(),
                    rejected.depositId(),
                    command.userId(),
                    command.chain(),
                    command.token(),
                    command.amount(),
                    command.txHash(),
                    properties.getDepositTokenWhitelist());
            return new DepositCreditResult(rejected, null, true);
        }

        TradingDeposit existing = tradingDepositRepository.findByWalletTxId(command.walletTxId())
                .orElse(null);
        if (existing != null) {
            tradingInboxRepository.markProcessedIfAbsent(
                    command.eventId(),
                    "wallet.deposit.confirmed",
                    command.confirmedAt()
            );
            TradingAccount account = tradingAccountService.getOrCreateAccount(existing.userId(), properties.getSettlementToken());
            log.info("trading.deposit.credit.duplicate walletTxId={} txHash={} depositId={} userId={}",
                    command.walletTxId(),
                    command.txHash(),
                    existing.depositId(),
                    existing.userId());
            return new DepositCreditResult(existing, account, true);
        }

        TradingAccount account = tradingAccountService.creditDeposit(
                command.userId(),
                properties.getSettlementToken(),
                command.amount(),
                "deposit-credit:" + command.walletTxId(),
                command.txHash(),
                command.confirmedAt()
        );
        TradingDeposit deposit = tradingDepositRepository.save(new TradingDeposit(
                null,
                command.walletTxId(),
                command.userId(),
                account.accountId(),
                command.chain(),
                command.token(),
                command.txHash(),
                command.amount(),
                TradingDepositStatus.CREDITED,
                command.confirmedAt(),
                null,
                null,
                null
        ));

        tradingOutboxRepository.save(new TradingOutboxMessage(
                null,
                "deposit-credited:" + command.walletTxId(),
                "trading.deposit.credited",
                String.valueOf(command.userId()),
                new DepositCreditedEventPayload(
                        deposit.depositId(),
                        command.userId(),
                        account.accountId(),
                        command.chain().name(),
                        command.token(),
                        command.txHash(),
                        command.amount(),
                        command.confirmedAt()
                ),
                TradingOutboxStatus.PENDING,
                command.confirmedAt(),
                null,
                0,
                command.confirmedAt(),
                null
        ));
        tradingInboxRepository.markProcessedIfAbsent(command.eventId(), "wallet.deposit.confirmed", command.confirmedAt());

        // STAGE-8-NOTIFICATION Phase 2：入金到账后发站内信
        try {
            notificationService.send(
                    "DEPOSIT_CREDITED",
                    command.userId(),
                    "DEPOSIT_CREDITED",
                    Map.of(
                            "amount", command.amount().toPlainString(),
                            "currency", command.token(),
                            "chain", command.chain().name(),
                            "txHash", command.txHash()
                    ),
                    "DEPOSIT",
                    deposit.depositId(),
                    null
            );
        } catch (RuntimeException ex) {
            // 通知失败不阻塞入金事实落库；deposit + account + outbox 已写完
            log.warn("trading.deposit.notification.send.failed walletTxId={} depositId={} reason={}",
                    command.walletTxId(), deposit.depositId(), ex.toString());
        }

        log.info("trading.deposit.credit.completed eventId={} walletTxId={} userId={} depositId={} accountId={}",
                command.eventId(),
                command.walletTxId(),
                command.userId(),
                deposit.depositId(),
                account.accountId());
        return new DepositCreditResult(deposit, account, false);
    }
}
