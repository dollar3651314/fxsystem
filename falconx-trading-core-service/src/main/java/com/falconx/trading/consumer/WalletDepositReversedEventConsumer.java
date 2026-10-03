package com.falconx.trading.consumer;

import com.falconx.trading.application.TradingDepositReversalApplicationService;
import com.falconx.trading.command.ReverseWalletDepositCommand;
import com.falconx.wallet.contract.event.WalletDepositReversedEventPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 钱包入金回滚事件消费者。
 *
 * <p>该消费者负责把钱包回滚事件交给交易域做业务事实回退，
 * 维持“原始链事实”和“最终业务入账事实”之间的一致性。
 */
@Component
public class WalletDepositReversedEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(WalletDepositReversedEventConsumer.class);

    private final TradingDepositReversalApplicationService tradingDepositReversalApplicationService;

    public WalletDepositReversedEventConsumer(TradingDepositReversalApplicationService tradingDepositReversalApplicationService) {
        this.tradingDepositReversalApplicationService = tradingDepositReversalApplicationService;
    }

    /**
     * 消费钱包回滚事件。
     *
     * @param eventId 事件 ID
     * @param payload 回滚 payload
     */
    public void consume(String eventId, WalletDepositReversedEventPayload payload) {
        log.info("trading.consumer.wallet.deposit.reversed eventId={} walletTxId={} userId={} txHash={}",
                eventId,
                payload.walletTxId(),
                payload.userId(),
                payload.txHash());
        tradingDepositReversalApplicationService.reverseDeposit(new ReverseWalletDepositCommand(
                eventId,
                payload.walletTxId(),
                payload.userId(),
                payload.chain(),
                payload.token(),
                payload.txHash(),
                payload.amount(),
                payload.reversedAt()
        ));
    }
}
