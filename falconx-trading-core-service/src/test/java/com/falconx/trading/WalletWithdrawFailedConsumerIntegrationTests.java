package com.falconx.trading;

import com.falconx.trading.application.WithdrawAdminApplicationService;
import com.falconx.trading.application.WithdrawSubmitApplicationService;
import com.falconx.trading.client.WithdrawKycLevelQueryClient;
import com.falconx.trading.client.WithdrawDepositAddressQueryClient;
import com.falconx.trading.client.WithdrawWhitelistQueryClient;
import com.falconx.trading.command.SubmitWithdrawCommand;
import com.falconx.trading.consumer.WalletWithdrawBroadcastEventConsumer;
import com.falconx.trading.consumer.WalletWithdrawFailedEventConsumer;
import com.falconx.trading.entity.TradingNotificationLevel;
import com.falconx.trading.entity.TradingWithdrawOrderStatus;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import com.falconx.wallet.contract.event.WalletWithdrawBroadcastedEventPayload;
import com.falconx.wallet.contract.event.WalletWithdrawFailedEventPayload;
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
 * STAGE-7-WITHDRAW Phase 3 R6：trading-core {@link WalletWithdrawFailedEventConsumer} IT。
 *
 * <p>覆盖：
 * <ul>
 *   <li>TC-WD-170 PROCESSING → FAILED + frozen=0（balance 不变）+ ledger biz_type=18(WITHDRAW_REFUND_CHAIN_FAILED)
 *       + 站内信 WARN title="出金失败" body 含 failureReason</li>
 *   <li>TC-WD-171 APPROVED → FAILED（广播前失败，txHash=null）→ frozen=0 + balance 不变 + ledger + 站内信</li>
 *   <li>TC-WD-172 重复 failed：relatedKey 命中后跳过；账户 / 账本 / 站内信不变</li>
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
class WalletWithdrawFailedConsumerIntegrationTests {

    private static final long USER_ID = 70170101L;
    private static final long ADMIN_ID = 70179001L;
    private static final long ACCOUNT_ID = 70170201L;
    private static final long WHITELIST_ID = 70170301L;
    private static final String ERC20_ADDRESS = "0xAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaa";
    private static final String TX_HASH = "0xfailed170" + "0".repeat(55);
    private static final String RELATED_KEY = "withdraw.failed";
    private static final int LEDGER_BIZ_WITHDRAW_REFUND_CHAIN_FAILED = 18;

    @Autowired
    private WithdrawSubmitApplicationService submitService;

    @Autowired
    private WithdrawAdminApplicationService adminService;

    @Autowired
    private WalletWithdrawBroadcastEventConsumer broadcastConsumer;

    @Autowired
    private WalletWithdrawFailedEventConsumer failedConsumer;

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

    /** TC-WD-170：PROCESSING → FAILED + frozen=0 + balance 不变 + ledger refund-chain-failed + 站内信 WARN。 */
    @Test
    void shouldRefundFrozenAndNotifyOnFailedWhenProcessing() {
        long withdrawId = submitApproveBroadcast("failed-170", "100");
        Assertions.assertEquals(TradingWithdrawOrderStatus.PROCESSING.code(),
                supportMapper.selectWithdrawOrderStatusCodeById(withdrawId).intValue());
        // 前置：frozen=100 / balance=50000
        Assertions.assertEquals(0, new BigDecimal(supportMapper.selectAccountFrozenByUserId(USER_ID))
                .compareTo(new BigDecimal("100.00000000")));

        failedConsumer.consume("evt-failed-170",
                new WalletWithdrawFailedEventPayload(withdrawId, USER_ID, "ERC20", TX_HASH,
                        "20011", "receipt status=0", OffsetDateTime.now(ZoneOffset.UTC)));

        Assertions.assertEquals(TradingWithdrawOrderStatus.FAILED.code(),
                supportMapper.selectWithdrawOrderStatusCodeById(withdrawId).intValue());
        // frozen=0 + balance 不变
        Assertions.assertEquals(0, new BigDecimal(supportMapper.selectAccountFrozenByUserId(USER_ID))
                .compareTo(BigDecimal.ZERO));
        Assertions.assertEquals(0, new BigDecimal(supportMapper.selectAccountBalanceByUserId(USER_ID))
                .compareTo(new BigDecimal("50000.00000000")));
        Assertions.assertEquals(1, (int) supportMapper.countLedgerByUserIdAndBizType(USER_ID,
                LEDGER_BIZ_WITHDRAW_REFUND_CHAIN_FAILED));
        // 站内信 WARN + "出金失败" + body 含 failureReason
        Assertions.assertEquals(1, (int) supportMapper.countNotificationByRelated(RELATED_KEY, withdrawId));
        Assertions.assertEquals(TradingNotificationLevel.WARN.code(),
                supportMapper.selectNotificationLevelCodeByRelated(RELATED_KEY, withdrawId).intValue());
        Assertions.assertEquals("出金失败",
                supportMapper.selectNotificationTitleByRelated(RELATED_KEY, withdrawId));
        String body = supportMapper.selectNotificationBodyByRelated(RELATED_KEY, withdrawId);
        Assertions.assertNotNull(body);
        Assertions.assertTrue(body.contains("receipt status=0"),
                "body 必须含 failureReason：实际=" + body);
    }

    /** TC-WD-171：APPROVED → FAILED（广播前失败 txHash=null）→ frozen=0 + balance 不变 + ledger + 站内信。 */
    @Test
    void shouldRefundFrozenAndNotifyOnFailedWhenApproved() {
        long withdrawId = submitAndApprove("failed-171", "100");
        Assertions.assertEquals(TradingWithdrawOrderStatus.APPROVED.code(),
                supportMapper.selectWithdrawOrderStatusCodeById(withdrawId).intValue());

        failedConsumer.consume("evt-failed-171",
                new WalletWithdrawFailedEventPayload(withdrawId, USER_ID, "ERC20", null,
                        "20014", "KmsSignerStub 兜底失败", OffsetDateTime.now(ZoneOffset.UTC)));

        Assertions.assertEquals(TradingWithdrawOrderStatus.FAILED.code(),
                supportMapper.selectWithdrawOrderStatusCodeById(withdrawId).intValue());
        Assertions.assertEquals(0, new BigDecimal(supportMapper.selectAccountFrozenByUserId(USER_ID))
                .compareTo(BigDecimal.ZERO));
        Assertions.assertEquals(0, new BigDecimal(supportMapper.selectAccountBalanceByUserId(USER_ID))
                .compareTo(new BigDecimal("50000.00000000")));
        Assertions.assertEquals(1, (int) supportMapper.countLedgerByUserIdAndBizType(USER_ID,
                LEDGER_BIZ_WITHDRAW_REFUND_CHAIN_FAILED));
        Assertions.assertEquals(1, (int) supportMapper.countNotificationByRelated(RELATED_KEY, withdrawId));
    }

    /** TC-WD-172：重复 failed → relatedKey 命中后跳过；账户 / 账本 / 站内信 不变。 */
    @Test
    void shouldRemainIdempotentOnDuplicateFailed() {
        long withdrawId = submitApproveBroadcast("failed-172", "100");
        WalletWithdrawFailedEventPayload payload = new WalletWithdrawFailedEventPayload(
                withdrawId, USER_ID, "ERC20", TX_HASH,
                "20011", "receipt status=0", OffsetDateTime.now(ZoneOffset.UTC));
        failedConsumer.consume("evt-failed-172-1", payload);

        BigDecimal frozenAfterFirst = new BigDecimal(supportMapper.selectAccountFrozenByUserId(USER_ID));
        int ledgerAfterFirst = supportMapper.countLedgerByUserIdAndBizType(USER_ID,
                LEDGER_BIZ_WITHDRAW_REFUND_CHAIN_FAILED);
        int notificationAfterFirst = supportMapper.countNotificationByRelated(RELATED_KEY, withdrawId);

        // 重复消费 → 直接 skipped-duplicate
        failedConsumer.consume("evt-failed-172-2", payload);

        Assertions.assertEquals(TradingWithdrawOrderStatus.FAILED.code(),
                supportMapper.selectWithdrawOrderStatusCodeById(withdrawId).intValue());
        Assertions.assertEquals(0, frozenAfterFirst.compareTo(
                new BigDecimal(supportMapper.selectAccountFrozenByUserId(USER_ID))),
                "frozen 不应在重复 failed 下二次减");
        Assertions.assertEquals(ledgerAfterFirst,
                supportMapper.countLedgerByUserIdAndBizType(USER_ID,
                        LEDGER_BIZ_WITHDRAW_REFUND_CHAIN_FAILED).intValue(),
                "ledger biz_type=18 不应在重复 failed 下二次写入");
        Assertions.assertEquals(notificationAfterFirst,
                supportMapper.countNotificationByRelated(RELATED_KEY, withdrawId).intValue(),
                "站内信不应在重复 failed 下二次写入");
    }

    private long submitAndApprove(String idemSuffix, String amount) {
        var submitted = submitService.submit(new SubmitWithdrawCommand(
                USER_ID, new BigDecimal(amount), "USDT", "ERC20", ERC20_ADDRESS, WHITELIST_ID,
                "idem-wd-failed-" + idemSuffix));
        supportMapper.updateWithdrawOrderStatus(submitted.id(), TradingWithdrawOrderStatus.PENDING.code());
        adminService.approve(submitted.id(), ADMIN_ID, "ok");
        return submitted.id();
    }

    private long submitApproveBroadcast(String idemSuffix, String amount) {
        long withdrawId = submitAndApprove(idemSuffix, amount);
        broadcastConsumer.consume("evt-broadcast-" + idemSuffix,
                new WalletWithdrawBroadcastedEventPayload(
                        withdrawId, USER_ID, "ERC20", TX_HASH,
                        0L, BigDecimal.ZERO, OffsetDateTime.now(ZoneOffset.UTC)));
        return withdrawId;
    }
}
