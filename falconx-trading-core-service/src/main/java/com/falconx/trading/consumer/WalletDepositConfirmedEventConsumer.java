package com.falconx.trading.consumer;

import com.falconx.trading.application.TradingDepositCreditApplicationService;
import com.falconx.trading.command.CreditConfirmedDepositCommand;
import com.falconx.wallet.contract.event.WalletDepositConfirmedEventPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 钱包确认入金事件消费者。
 *
 * <p>该消费者是 `wallet-service -> trading-core-service` 入账链路的入口，
 * 负责把外部事件 payload 适配为应用层命令对象，再交由应用服务处理。
 */
@Component
public class WalletDepositConfirmedEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(WalletDepositConfirmedEventConsumer.class);

    private final TradingDepositCreditApplicationService tradingDepositCreditApplicationService;

    public WalletDepositConfirmedEventConsumer(TradingDepositCreditApplicationService tradingDepositCreditApplicationService) {
        this.tradingDepositCreditApplicationService = tradingDepositCreditApplicationService;
    }

    /**
     * 消费钱包确认入金事件。
     *
     * @param eventId 事件 ID
     * @param payload 钱包确认入金 payload
     */
    public void consume(String eventId, WalletDepositConfirmedEventPayload payload) {
        log.info("trading.consumer.wallet.deposit.confirmed eventId={} walletTxId={} userId={} txHash={}",
                eventId,
                payload.walletTxId(),
                payload.userId(),
                payload.txHash());
        tradingDepositCreditApplicationService.creditConfirmedDeposit(new CreditConfirmedDepositCommand(
                eventId,
                payload.walletTxId(),
                payload.userId(),
                payload.chain(),
                payload.token(),
                payload.txHash(),
                payload.amount(),
                payload.confirmedAt()
        ));
    }
}
