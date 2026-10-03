package com.falconx.trading;

import com.falconx.trading.application.WithdrawCancelApplicationService;
import com.falconx.trading.application.WithdrawSubmitApplicationService;
import com.falconx.trading.client.WithdrawKycLevelQueryClient;
import com.falconx.trading.client.WithdrawDepositAddressQueryClient;
import com.falconx.trading.client.WithdrawWhitelistQueryClient;
import com.falconx.trading.command.SubmitWithdrawCommand;
import com.falconx.trading.entity.TradingWithdrawOrder;
import com.falconx.trading.entity.TradingWithdrawOrderStatus;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.TradingWithdrawOrderRepository;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
 * STAGE-7-WITHDRAW Phase 2 R6 测试落地：POST /{id}/cancel 应用层 IT。
 *
 * <p>覆盖 TC-WD-016 / 017（详见 docs/test/STAGE-7-WITHDRAW-test-cases.md）。
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
                "spring.data.redis.port=6380"
        }
)
class WithdrawCancelIntegrationTests {

    private static final long USER_ID = 70020101L;
    private static final long USER_B = 70020102L;
    private static final long ACCOUNT_ID = 70020201L;
    private static final long WHITELIST_ID = 70020301L;
    private static final String ERC20_ADDRESS = "0xAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaa";

    @Autowired
    private WithdrawSubmitApplicationService submitService;

    @Autowired
    private WithdrawCancelApplicationService cancelService;

    @Autowired
    private TradingWithdrawOrderRepository withdrawOrderRepository;

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

    /** TC-WD-016：COOLING 用户取消 → status=CANCELED + frozen -= amount + ledger biz_type=14。 */
    @Test
    void shouldCancelCoolingOrderAndRefundFrozen() {
        TradingWithdrawOrder submitted = submitService.submit(new SubmitWithdrawCommand(
                USER_ID, new BigDecimal("100.50"), "USDT", "ERC20",
                ERC20_ADDRESS, WHITELIST_ID, "idem-tc-wd-016"));
        // 前置断言：提交后 frozen=100.5 + ledger biz_type=13 一行
        Assertions.assertEquals(0, new BigDecimal(supportMapper.selectAccountFrozenByUserId(USER_ID))
                .compareTo(new BigDecimal("100.50000000")));
        Assertions.assertEquals(1, supportMapper.countLedgerByUserIdAndBizType(USER_ID, 13));

        TradingWithdrawOrder canceled = cancelService.cancel(USER_ID, submitted.id());

        Assertions.assertEquals(TradingWithdrawOrderStatus.CANCELED, canceled.status());
        // DB 状态 = 7
        Assertions.assertEquals(7, supportMapper.selectWithdrawOrderStatusCodeById(submitted.id()));
        // frozen 回到 0
        Assertions.assertEquals(0, new BigDecimal(supportMapper.selectAccountFrozenByUserId(USER_ID))
                .compareTo(BigDecimal.ZERO));
        // ledger 多了一行 biz_type=14
        Assertions.assertEquals(1, supportMapper.countLedgerByUserIdAndBizType(USER_ID, 14));
    }

    /** TC-WD-017：状态非 COOLING（这里通过直接 SQL 切到 PENDING 模拟）→ 30048。 */
    @Test
    void shouldRejectCancelWhenStatusIsPending() {
        TradingWithdrawOrder submitted = submitService.submit(new SubmitWithdrawCommand(
                USER_ID, new BigDecimal("100"), "USDT", "ERC20",
                ERC20_ADDRESS, WHITELIST_ID, "idem-tc-wd-017"));
        // 模拟 cooling 期已过，调度器把状态切到 PENDING
        supportMapper.updateWithdrawOrderStatus(submitted.id(), 1);

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> cancelService.cancel(USER_ID, submitted.id()));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_NOT_CANCELABLE, ex.getErrorCode());
        // 状态仍是 1=PENDING，未变
        Assertions.assertEquals(1, supportMapper.selectWithdrawOrderStatusCodeById(submitted.id()));
        // frozen 仍冻结
        Assertions.assertEquals(0, new BigDecimal(supportMapper.selectAccountFrozenByUserId(USER_ID))
                .compareTo(new BigDecimal("100.00000000")));
    }

    /** 取消他人的单 → 30047 NOT_FOUND（不暴露归属信息）。 */
    @Test
    void shouldRejectCancelForOrderNotOwnedByUser() {
        TradingWithdrawOrder submitted = submitService.submit(new SubmitWithdrawCommand(
                USER_ID, new BigDecimal("100"), "USDT", "ERC20",
                ERC20_ADDRESS, WHITELIST_ID, "idem-tc-wd-017-other"));

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> cancelService.cancel(USER_B, submitted.id()));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_NOT_FOUND, ex.getErrorCode());
    }

    /** 取消不存在的单 → 30047 NOT_FOUND。 */
    @Test
    void shouldRejectCancelForNonExistentOrder() {
        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> cancelService.cancel(USER_ID, 99999999L));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_NOT_FOUND, ex.getErrorCode());
    }
}
