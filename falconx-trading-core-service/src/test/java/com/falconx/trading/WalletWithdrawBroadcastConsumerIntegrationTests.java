package com.falconx.trading;

import com.falconx.trading.application.WithdrawAdminApplicationService;
import com.falconx.trading.application.WithdrawSubmitApplicationService;
import com.falconx.trading.client.WithdrawKycLevelQueryClient;
import com.falconx.trading.client.WithdrawDepositAddressQueryClient;
import com.falconx.trading.client.WithdrawWhitelistQueryClient;
import com.falconx.trading.command.SubmitWithdrawCommand;
import com.falconx.trading.consumer.WalletWithdrawBroadcastEventConsumer;
import com.falconx.trading.entity.TradingWithdrawOrderStatus;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import com.falconx.wallet.contract.event.WalletWithdrawBroadcastedEventPayload;
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
 * STAGE-7-WITHDRAW Phase 3 R6：trading-core {@link WalletWithdrawBroadcastEventConsumer} IT。
 *
 * <p>覆盖：
 * <ul>
 *   <li>TC-WD-150 APPROVED → PROCESSING + tx_hash 写入 + processing_started_at</li>
 *   <li>TC-WD-151 重复 broadcast 幂等：状态已 PROCESSING 时 CAS 0 行；status 不变</li>
 *   <li>TC-WD-152 APPROVED_DELAYED 收到 broadcast：CAS 0 行；warn 日志；不抛异常</li>
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
class WalletWithdrawBroadcastConsumerIntegrationTests {

    private static final long USER_ID = 70150101L;
    private static final long ADMIN_ID = 70159001L;
    private static final long ACCOUNT_ID = 70150201L;
    private static final long WHITELIST_ID = 70150301L;
    private static final String ERC20_ADDRESS = "0xAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaa";
    private static final String TX_HASH = "0xchainbroadcast150" + "0".repeat(47);

    @Autowired
    private WithdrawSubmitApplicationService submitService;

    @Autowired
    private WithdrawAdminApplicationService adminService;

    @Autowired
    private WalletWithdrawBroadcastEventConsumer broadcastConsumer;

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

    /** TC-WD-150：APPROVED → PROCESSING + tx_hash + processing_started_at。 */
    @Test
    void shouldTransitionApprovedToProcessingOnBroadcast() {
        long withdrawId = submitAndApprove("broadcast-150", "100");
        Assertions.assertEquals(TradingWithdrawOrderStatus.APPROVED.code(),
                supportMapper.selectWithdrawOrderStatusCodeById(withdrawId).intValue());

        broadcastConsumer.consume("evt-broadcast-150", newBroadcastedPayload(withdrawId, TX_HASH));

        Assertions.assertEquals(TradingWithdrawOrderStatus.PROCESSING.code(),
                supportMapper.selectWithdrawOrderStatusCodeById(withdrawId).intValue());
    }

    /** TC-WD-151：重复 broadcast 幂等：第二次 CAS 0 行；status 仍 PROCESSING；不抛异常。 */
    @Test
    void shouldRemainProcessingWhenBroadcastedTwice() {
        long withdrawId = submitAndApprove("broadcast-151", "100");
        broadcastConsumer.consume("evt-broadcast-151-1", newBroadcastedPayload(withdrawId, TX_HASH));
        Assertions.assertEquals(TradingWithdrawOrderStatus.PROCESSING.code(),
                supportMapper.selectWithdrawOrderStatusCodeById(withdrawId).intValue());

        // 二次发送（重投）→ CAS 不再命中，status 不变；不抛异常
        broadcastConsumer.consume("evt-broadcast-151-2",
                newBroadcastedPayload(withdrawId, "0xduplicate" + "0".repeat(54)));

        Assertions.assertEquals(TradingWithdrawOrderStatus.PROCESSING.code(),
                supportMapper.selectWithdrawOrderStatusCodeById(withdrawId).intValue());
    }

    /** TC-WD-152：APPROVED_DELAYED 时收到 broadcast → CAS 不命中；status 仍 APPROVED_DELAYED；不抛。 */
    @Test
    void shouldSkipBroadcastWhenStatusIsApprovedDelayed() {
        long withdrawId = submitAndApprove("broadcast-152", "5000"); // ≥3K 触发 APPROVED_DELAYED
        Assertions.assertEquals(TradingWithdrawOrderStatus.APPROVED_DELAYED.code(),
                supportMapper.selectWithdrawOrderStatusCodeById(withdrawId).intValue());

        broadcastConsumer.consume("evt-broadcast-152", newBroadcastedPayload(withdrawId, TX_HASH));

        // 状态没动；delayed 出金必须等 trading-core 内部调度器二次发 reviewed APPROVED 再触发 broadcast
        Assertions.assertEquals(TradingWithdrawOrderStatus.APPROVED_DELAYED.code(),
                supportMapper.selectWithdrawOrderStatusCodeById(withdrawId).intValue());
    }

    private long submitAndApprove(String idemSuffix, String amount) {
        var submitted = submitService.submit(new SubmitWithdrawCommand(
                USER_ID, new BigDecimal(amount), "USDT", "ERC20", ERC20_ADDRESS, WHITELIST_ID,
                "idem-wd-broadcast-" + idemSuffix));
        supportMapper.updateWithdrawOrderStatus(submitted.id(), TradingWithdrawOrderStatus.PENDING.code());
        adminService.approve(submitted.id(), ADMIN_ID, "ok");
        return submitted.id();
    }

    private static WalletWithdrawBroadcastedEventPayload newBroadcastedPayload(long withdrawId, String txHash) {
        return new WalletWithdrawBroadcastedEventPayload(
                withdrawId, USER_ID, "ERC20", txHash,
                0L, BigDecimal.ZERO, OffsetDateTime.now(ZoneOffset.UTC));
    }
}
