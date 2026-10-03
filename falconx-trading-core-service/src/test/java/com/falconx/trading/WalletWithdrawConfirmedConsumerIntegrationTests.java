package com.falconx.trading;

import com.falconx.trading.application.WithdrawAdminApplicationService;
import com.falconx.trading.application.WithdrawSubmitApplicationService;
import com.falconx.trading.client.WithdrawKycLevelQueryClient;
import com.falconx.trading.client.WithdrawDepositAddressQueryClient;
import com.falconx.trading.client.WithdrawWhitelistQueryClient;
import com.falconx.trading.command.SubmitWithdrawCommand;
import com.falconx.trading.consumer.WalletWithdrawBroadcastEventConsumer;
import com.falconx.trading.consumer.WalletWithdrawConfirmedEventConsumer;
import com.falconx.trading.entity.TradingNotificationLevel;
import com.falconx.trading.entity.TradingWithdrawOrderStatus;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import com.falconx.wallet.contract.event.WalletWithdrawBroadcastedEventPayload;
import com.falconx.wallet.contract.event.WalletWithdrawConfirmedEventPayload;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * STAGE-7-WITHDRAW Phase 3 R6：trading-core {@link WalletWithdrawConfirmedEventConsumer} IT。
 *
 * <p>覆盖：
 * <ul>
 *   <li>TC-WD-160 PROCESSING → COMPLETED + balance -= amount + frozen=0 + ledger biz_type=17(WITHDRAW_SETTLE)
 *       + 站内信 INFO title="出金已完成"</li>
 *   <li>TC-WD-161 重复 confirmed：notificationRepository.existsByRelated 命中后跳过；账户与账本不变</li>
 *   <li>TC-WD-162 站内信 t_notification 1 行 user_id 匹配 + relatedKey=withdraw.confirmed + relatedId=withdrawId</li>
 * </ul>
 */
@ActiveProfiles("stage5")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = TradingCoreServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_trading_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380",
                "falconx.trading.withdraw-cooling-scheduler.interval-ms=86400000",
                "falconx.trading.withdraw-delayed-scheduler.interval-ms=86400000"
        }
)
class WalletWithdrawConfirmedConsumerIntegrationTests {

    private static final long USER_ID = 70160101L;
    private static final long ADMIN_ID = 70169001L;
    private static final long ACCOUNT_ID = 70160201L;
    private static final long WHITELIST_ID = 70160301L;
    private static final String ERC20_ADDRESS = "0xAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaa";
    private static final String TX_HASH = "0xconfirmed160" + "0".repeat(52);
    private static final String RELATED_KEY = "withdraw.confirmed";
    private static final int LEDGER_BIZ_WITHDRAW_SETTLE = 17;

    @Autowired
    private WithdrawSubmitApplicationService submitService;

    @Autowired
    private WithdrawAdminApplicationService adminService;

    @Autowired
    private WalletWithdrawBroadcastEventConsumer broadcastConsumer;

    @Autowired
    private WalletWithdrawConfirmedEventConsumer confirmedConsumer;

    @Autowired
    private TradingTestSupportMapper supportMapper;

    @MockitoBean
    private WithdrawKycLevelQueryClient kycLevelQueryClient;

    @MockitoBean
    private WithdrawWhitelistQueryClient whitelistQueryClient;


    @MockitoBean
    private WithdrawDepositAddressQueryClient depositAddressQueryClient;
    @BeforeEach
    void cleanAndSeed() {
        supportMapper.clearOwnerTables();
        supportMapper.insertSeedAccount(ACCOUNT_ID, USER_ID, "USDT",
                new BigDecimal("50000.00000000"), BigDecimal.ZERO, BigDecimal.ZERO);
        Mockito.reset(kycLevelQueryClient, whitelistQueryClient, depositAddressQueryClient);
        Mockito.when(kycLevelQueryClient.queryKycLevel(USER_ID)).thenReturn(1);
        Mockito.when(whitelistQueryClient.findById(WHITELIST_ID))
                .thenReturn(Optional.of(new WithdrawWhitelistQueryClient.WhitelistView(
                        WHITELIST_ID, USER_ID, "ERC20", ERC20_ADDRESS, null, "ACTIVE",
                        OffsetDateTime.now().minusHours(48),
                        OffsetDateTime.now().minusHours(48))));
            // STAGE-6-KYC trigger 2: 把出金地址当作历史 from_address，让 unfamiliar 校验通过
        Mockito.when(depositAddressQueryClient.listConfirmedFromAddresses(USER_ID, "ETH"))
                .thenReturn(java.util.List.of(ERC20_ADDRESS));
}

    /** TC-WD-160：PROCESSING → COMPLETED + balance -= 100 + frozen=0 + ledger settle + 站内信 INFO。 */
    @Test
    void shouldSettleAccountAndCreateNotificationOnConfirmed() {
        long withdrawId = submitApproveBroadcast("confirmed-160", "100");
        Assertions.assertEquals(TradingWithdrawOrderStatus.PROCESSING.code(),
                supportMapper.selectWithdrawOrderStatusCodeById(withdrawId).intValue());
        // 前置：frozen=100 / balance=50000
        Assertions.assertEquals(0, new BigDecimal(supportMapper.selectAccountFrozenByUserId(USER_ID))
                .compareTo(new BigDecimal("100.00000000")));
        Assertions.assertEquals(0, new BigDecimal(supportMapper.selectAccountBalanceByUserId(USER_ID))
                .compareTo(new BigDecimal("50000.00000000")));

        confirmedConsumer.consume("evt-confirmed-160", newConfirmedPayload(withdrawId));

        Assertions.assertEquals(TradingWithdrawOrderStatus.COMPLETED.code(),
                supportMapper.selectWithdrawOrderStatusCodeById(withdrawId).intValue());
        // frozen=0 + balance=49900
        Assertions.assertEquals(0, new BigDecimal(supportMapper.selectAccountFrozenByUserId(USER_ID))
                .compareTo(BigDecimal.ZERO));
        Assertions.assertEquals(0, new BigDecimal(supportMapper.selectAccountBalanceByUserId(USER_ID))
                .compareTo(new BigDecimal("49900.00000000")));
        Assertions.assertEquals(1, (int) supportMapper.countLedgerByUserIdAndBizType(USER_ID, LEDGER_BIZ_WITHDRAW_SETTLE));
        // 站内信 INFO + "出金已完成"
        Assertions.assertEquals(1, (int) supportMapper.countNotificationByRelated(RELATED_KEY, withdrawId));
        Assertions.assertEquals(TradingNotificationLevel.INFO.code(),
                supportMapper.selectNotificationLevelCodeByRelated(RELATED_KEY, withdrawId).intValue());
        Assertions.assertEquals("出金已完成",
                supportMapper.selectNotificationTitleByRelated(RELATED_KEY, withdrawId));
    }

    /** TC-WD-161：重复 confirmed → relatedKey 已存在跳过；账户 / 账本 / 站内信均无变化。 */
    @Test
    void shouldRemainIdempotentOnDuplicateConfirmed() {
        long withdrawId = submitApproveBroadcast("confirmed-161", "100");
        confirmedConsumer.consume("evt-confirmed-161-1", newConfirmedPayload(withdrawId));
        // 第一次后 state snapshot
        BigDecimal balanceAfterFirst = new BigDecimal(supportMapper.selectAccountBalanceByUserId(USER_ID));
        int ledgerAfterFirst = supportMapper.countLedgerByUserIdAndBizType(USER_ID, LEDGER_BIZ_WITHDRAW_SETTLE);
        int notificationAfterFirst = supportMapper.countNotificationByRelated(RELATED_KEY, withdrawId);

        // 重复消费 → 应直接跳过
        confirmedConsumer.consume("evt-confirmed-161-2", newConfirmedPayload(withdrawId));

        Assertions.assertEquals(TradingWithdrawOrderStatus.COMPLETED.code(),
                supportMapper.selectWithdrawOrderStatusCodeById(withdrawId).intValue());
        Assertions.assertEquals(0, balanceAfterFirst.compareTo(
                new BigDecimal(supportMapper.selectAccountBalanceByUserId(USER_ID))),
                "balance 不应在重复消费下二次扣减");
        Assertions.assertEquals(ledgerAfterFirst,
                supportMapper.countLedgerByUserIdAndBizType(USER_ID, LEDGER_BIZ_WITHDRAW_SETTLE).intValue(),
                "ledger biz_type=17 不应在重复消费下二次写入");
        Assertions.assertEquals(notificationAfterFirst,
                supportMapper.countNotificationByRelated(RELATED_KEY, withdrawId).intValue(),
                "站内信不应在重复消费下二次写入");
    }

    /** TC-WD-162：t_notification 写入 user_id + relatedKey=withdraw.confirmed + relatedId=withdrawId + body 含金额币种。 */
    @Test
    void shouldPersistNotificationWithCorrectRelatedKeyAndBody() {
        long withdrawId = submitApproveBroadcast("confirmed-162", "100");

        confirmedConsumer.consume("evt-confirmed-162", newConfirmedPayload(withdrawId));

        Assertions.assertEquals(1, (int) supportMapper.countNotificationByUserId(USER_ID));
        Assertions.assertEquals(1, (int) supportMapper.countNotificationByRelated(RELATED_KEY, withdrawId));
        // STAGE-8-NOTIFICATION 后 t_notification.type 列改存 templateCode（V22 schema）
        Assertions.assertEquals("WITHDRAW_COMPLETED",
                supportMapper.selectNotificationTypeByRelated(RELATED_KEY, withdrawId));
        String body = supportMapper.selectNotificationBodyByRelated(RELATED_KEY, withdrawId);
        Assertions.assertNotNull(body);
        Assertions.assertTrue(body.contains("USDT") && body.contains("100"),
                "body 必须含金额币种：实际=" + body);
    }

    private long submitApproveBroadcast(String idemSuffix, String amount) {
        var submitted = submitService.submit(new SubmitWithdrawCommand(
                USER_ID, new BigDecimal(amount), "USDT", "ERC20", ERC20_ADDRESS, WHITELIST_ID,
                "idem-wd-confirmed-" + idemSuffix));
        supportMapper.updateWithdrawOrderStatus(submitted.id(), TradingWithdrawOrderStatus.PENDING.code());
        adminService.approve(submitted.id(), ADMIN_ID, "ok");
        broadcastConsumer.consume("evt-broadcast-" + idemSuffix,
                new WalletWithdrawBroadcastedEventPayload(
                        submitted.id(), USER_ID, "ERC20", TX_HASH,
                        0L, BigDecimal.ZERO, OffsetDateTime.now(ZoneOffset.UTC)));
        return submitted.id();
    }

    private static WalletWithdrawConfirmedEventPayload newConfirmedPayload(long withdrawId) {
        return new WalletWithdrawConfirmedEventPayload(
                withdrawId, USER_ID, "ERC20", TX_HASH,
                19000000L, 12, OffsetDateTime.now(ZoneOffset.UTC));
    }
}
