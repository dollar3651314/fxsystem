package com.falconx.trading;

import com.falconx.trading.application.WithdrawCoolingScheduler;
import com.falconx.trading.application.WithdrawSubmitApplicationService;
import com.falconx.trading.client.WithdrawKycLevelQueryClient;
import com.falconx.trading.client.WithdrawDepositAddressQueryClient;
import com.falconx.trading.client.WithdrawWhitelistQueryClient;
import com.falconx.trading.command.SubmitWithdrawCommand;
import com.falconx.trading.entity.TradingWithdrawOrder;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
 * STAGE-7-WITHDRAW Phase 2 R6：trading-core 出金冷静期调度器 IT。
 *
 * <p>覆盖 TC-WD-060 / 061。
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
                // 测试中关闭定时触发，单元方式调用 advance()
                "falconx.trading.withdraw-cooling-scheduler.interval-ms=86400000"
        }
)
class WithdrawCoolingSchedulerIntegrationTests {

    private static final long USER_ID = 70040101L;
    private static final long ACCOUNT_ID = 70040201L;
    private static final long WHITELIST_ID = 70040301L;
    private static final String ERC20_ADDRESS = "0xAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaa";

    @Autowired
    private WithdrawSubmitApplicationService submitService;

    @Autowired
    private WithdrawCoolingScheduler scheduler;

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
                new BigDecimal("1000.00000000"), BigDecimal.ZERO, BigDecimal.ZERO);
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

    /** TC-WD-060：cooling_until 已过 → 调度器推进 COOLING(0) → PENDING(1)。 */
    @Test
    void shouldPromoteExpiredCoolingOrderToPending() {
        TradingWithdrawOrder submitted = submitService.submit(new SubmitWithdrawCommand(
                USER_ID, new BigDecimal("100"), "USDT", "ERC20", ERC20_ADDRESS, WHITELIST_ID,
                "idem-tc-wd-060"));
        // 模拟 cooling_until 已过 1 小时
        supportMapper.updateWithdrawOrderCoolingUntil(submitted.id(),
                LocalDateTime.now(ZoneOffset.UTC).minusHours(1));

        int promoted = scheduler.advance();

        Assertions.assertEquals(1, promoted);
        Assertions.assertEquals(1, supportMapper.selectWithdrawOrderStatusCodeById(submitted.id()));
    }

    /** TC-WD-061：cooling_until 未到 → 调度器不推进。 */
    @Test
    void shouldNotPromoteOrderWithFutureCoolingUntil() {
        TradingWithdrawOrder submitted = submitService.submit(new SubmitWithdrawCommand(
                USER_ID, new BigDecimal("100"), "USDT", "ERC20", ERC20_ADDRESS, WHITELIST_ID,
                "idem-tc-wd-061"));
        // cooling_until 默认 = now + 2h，调度器看到未到期

        int promoted = scheduler.advance();

        Assertions.assertEquals(0, promoted);
        Assertions.assertEquals(0, supportMapper.selectWithdrawOrderStatusCodeById(submitted.id()));
    }

    /** TC-WD-065：CAS 并发安全 - 状态被其他线程切走后调度器再扫到，CAS 返回 0。 */
    @Test
    void shouldHandleConcurrentStatusChangeGracefully() {
        TradingWithdrawOrder submitted = submitService.submit(new SubmitWithdrawCommand(
                USER_ID, new BigDecimal("100"), "USDT", "ERC20", ERC20_ADDRESS, WHITELIST_ID,
                "idem-tc-wd-065"));
        supportMapper.updateWithdrawOrderCoolingUntil(submitted.id(),
                LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        // 模拟用户在调度器扫描和 CAS 之间取消（状态切到 CANCELED=7）
        supportMapper.updateWithdrawOrderStatus(submitted.id(), 7);

        int promoted = scheduler.advance();

        Assertions.assertEquals(0, promoted);
        // 状态仍为 CANCELED
        Assertions.assertEquals(7, supportMapper.selectWithdrawOrderStatusCodeById(submitted.id()));
    }
}
