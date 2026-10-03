package com.falconx.trading;

import com.falconx.trading.application.WithdrawAdminApplicationService;
import com.falconx.trading.application.WithdrawDelayedScheduler;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * STAGE-7-WITHDRAW Phase 2 R6：APPROVED_DELAYED 推进调度器 IT。
 *
 * <p>覆盖 TC-WD-062 / 063。
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
class WithdrawDelayedSchedulerIntegrationTests {

    private static final long USER_ID = 70060101L;
    private static final long ADMIN_ID = 70069001L;
    private static final long ACCOUNT_ID = 70060201L;
    private static final long WHITELIST_ID = 70060301L;
    private static final String ERC20_ADDRESS = "0xAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaa";

    @Autowired
    private WithdrawSubmitApplicationService submitService;

    @Autowired
    private WithdrawAdminApplicationService adminService;

    @Autowired
    private WithdrawDelayedScheduler scheduler;

    @Autowired
    private TradingTestSupportMapper supportMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    /** TC-WD-062：APPROVED_DELAYED + delayed_until 已过 → 调度器推进 APPROVED。 */
    @Test
    void shouldPromoteExpiredApprovedDelayedToApproved() {
        long withdrawId = submitAndApproveDelayed("delayed-promote", "5000");
        // 模拟 delayed_until 已过 1 小时
        jdbcTemplate.update("UPDATE t_withdraw_order SET delayed_until = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusHours(1), withdrawId);

        int promoted = scheduler.advance();

        Assertions.assertEquals(1, promoted);
        Assertions.assertEquals(2, supportMapper.selectWithdrawOrderStatusCodeById(withdrawId));
    }

    /** TC-WD-063：APPROVED_DELAYED + delayed_until 未到 → 不推进。 */
    @Test
    void shouldNotPromoteWhenDelayedUntilNotReached() {
        long withdrawId = submitAndApproveDelayed("delayed-future", "5000");
        // delayed_until = 现在 + 6h，未到

        int promoted = scheduler.advance();

        Assertions.assertEquals(0, promoted);
        Assertions.assertEquals(3, supportMapper.selectWithdrawOrderStatusCodeById(withdrawId));
    }

    private long submitAndApproveDelayed(String idemSuffix, String amount) {
        TradingWithdrawOrder submitted = submitService.submit(new SubmitWithdrawCommand(
                USER_ID, new BigDecimal(amount), "USDT", "ERC20", ERC20_ADDRESS, WHITELIST_ID,
                "idem-wd-delayed-" + idemSuffix));
        supportMapper.updateWithdrawOrderStatus(submitted.id(), 1);
        adminService.approve(submitted.id(), ADMIN_ID, "approve-large");
        return submitted.id();
    }
}
