package com.falconx.trading;

import com.falconx.trading.application.WithdrawQueryApplicationService;
import com.falconx.trading.application.WithdrawSubmitApplicationService;
import com.falconx.trading.client.WithdrawKycLevelQueryClient;
import com.falconx.trading.client.WithdrawDepositAddressQueryClient;
import com.falconx.trading.client.WithdrawWhitelistQueryClient;
import com.falconx.trading.command.SubmitWithdrawCommand;
import com.falconx.trading.entity.TradingWithdrawOrder;
import com.falconx.trading.entity.TradingWithdrawOrderStatus;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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
 * STAGE-7-WITHDRAW Phase 1 R6 测试落地：GET 列表 + 详情 IT。
 *
 * <p>覆盖 TC-WD-012 / 013 / 014 / 015（详见 docs/test/STAGE-7-WITHDRAW-test-cases.md）。
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
class WithdrawQueryIntegrationTests {

    private static final long USER_A = 70010101L;
    private static final long USER_B = 70010102L;
    private static final long ACCOUNT_A = 70010201L;
    private static final long WHITELIST_A = 70010301L;
    private static final String ERC20_ADDRESS = "0xAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaa";

    @Autowired
    private WithdrawSubmitApplicationService submitService;

    @Autowired
    private WithdrawQueryApplicationService queryService;

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
        supportMapper.insertSeedAccount(ACCOUNT_A, USER_A, "USDT",
                new BigDecimal("50000.00000000"), BigDecimal.ZERO, BigDecimal.ZERO);
        Mockito.reset(kycLevelQueryClient, whitelistQueryClient, depositAddressQueryClient);
        Mockito.when(kycLevelQueryClient.queryKycLevel(USER_A)).thenReturn(1);
        Mockito.when(whitelistQueryClient.findById(WHITELIST_A))
                .thenReturn(Optional.of(new WithdrawWhitelistQueryClient.WhitelistView(
                        WHITELIST_A, USER_A, "ERC20", ERC20_ADDRESS, null, "ACTIVE",
                        OffsetDateTime.now().minusHours(48),
                        OffsetDateTime.now().minusHours(48))));
            // STAGE-6-KYC trigger 2: 把出金地址当作历史 from_address，让 unfamiliar 校验通过
        Mockito.when(depositAddressQueryClient.listConfirmedFromAddresses(USER_A, "ETH"))
                .thenReturn(java.util.List.of(ERC20_ADDRESS));
        // STAGE-6-KYC trigger 2: 把出金地址当作历史 from_address，让 unfamiliar 校验通过
        Mockito.when(depositAddressQueryClient.listConfirmedFromAddresses(USER_B, "ETH"))
                .thenReturn(java.util.List.of(ERC20_ADDRESS));
}

    /** TC-WD-012：GET 列表分页（创建 3 条，page=1 size=2 返回 2 条最新；page=2 size=2 返回最旧 1 条）。 */
    @Test
    void shouldPaginateUserList() {
        TradingWithdrawOrder a = submitService.submit(buildCommand("100", "page-a"));
        TradingWithdrawOrder b = submitService.submit(buildCommand("200", "page-b"));
        TradingWithdrawOrder c = submitService.submit(buildCommand("300", "page-c"));

        List<TradingWithdrawOrder> page1 = queryService.list(USER_A, null, 1, 2);
        Assertions.assertEquals(2, page1.size());
        // created_at DESC，c 最新 → page1[0]
        Assertions.assertEquals(c.id(), page1.get(0).id());
        Assertions.assertEquals(b.id(), page1.get(1).id());

        List<TradingWithdrawOrder> page2 = queryService.list(USER_A, null, 2, 2);
        Assertions.assertEquals(1, page2.size());
        Assertions.assertEquals(a.id(), page2.get(0).id());

        Assertions.assertEquals(3, queryService.count(USER_A, null));
    }

    /** TC-WD-013：GET 列表 status 过滤（仅返回指定状态）。 */
    @Test
    void shouldFilterListByStatus() {
        submitService.submit(buildCommand("100", "filter-a"));
        submitService.submit(buildCommand("200", "filter-b"));

        List<TradingWithdrawOrder> cooling = queryService.list(USER_A, "COOLING", 1, 20);
        Assertions.assertEquals(2, cooling.size());
        cooling.forEach(o -> Assertions.assertEquals(TradingWithdrawOrderStatus.COOLING, o.status()));

        List<TradingWithdrawOrder> completed = queryService.list(USER_A, "COMPLETED", 1, 20);
        Assertions.assertEquals(0, completed.size());

        // 未知 status → 空集
        List<TradingWithdrawOrder> unknown = queryService.list(USER_A, "UNKNOWN_STATUS", 1, 20);
        Assertions.assertEquals(0, unknown.size());
    }

    /** TC-WD-014：GET 详情 id 不存在 → 30047。 */
    @Test
    void shouldThrow30047WhenWithdrawNotFound() {
        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> queryService.getOwnedByUserOrThrow(USER_A, 99999999L));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_NOT_FOUND, ex.getErrorCode());
    }

    /** TC-WD-015：GET 详情非己（A 的单 B 来查）→ 30047。 */
    @Test
    void shouldThrow30047WhenWithdrawDoesNotBelongToUser() {
        TradingWithdrawOrder order = submitService.submit(buildCommand("100", "owned-by-a"));

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> queryService.getOwnedByUserOrThrow(USER_B, order.id()));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_NOT_FOUND, ex.getErrorCode());
    }

    /** 详情正向：归属正确 → 返回；status 与 amount 一致。 */
    @Test
    void shouldReturnOwnedWithdrawDetail() {
        TradingWithdrawOrder order = submitService.submit(buildCommand("100", "owner-detail"));

        TradingWithdrawOrder loaded = queryService.getOwnedByUserOrThrow(USER_A, order.id());
        Assertions.assertEquals(order.id(), loaded.id());
        Assertions.assertEquals(TradingWithdrawOrderStatus.COOLING, loaded.status());
        Assertions.assertEquals(0, loaded.amount().compareTo(new BigDecimal("100")));
    }

    private SubmitWithdrawCommand buildCommand(String amount, String idemSuffix) {
        return new SubmitWithdrawCommand(USER_A, new BigDecimal(amount), "USDT", "ERC20",
                ERC20_ADDRESS, WHITELIST_A, "idem-tc-wd-query-" + idemSuffix);
    }
}
