package com.falconx.trading;

import com.falconx.trading.application.WithdrawSubmitApplicationService;
import com.falconx.trading.client.WithdrawDepositAddressQueryClient;
import com.falconx.trading.client.WithdrawKycLevelQueryClient;
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
 * STAGE-7-WITHDRAW Phase 1 R6 测试落地：POST /api/v1/me/withdraw 应用层 IT。
 *
 * <p>覆盖 TC-WD-001 / 002 / 003 / 004 / 005 / 011（详见 docs/test/STAGE-7-WITHDRAW-test-cases.md）。
 *
 * <p>真实 DB + Redis；通过 {@link MockitoBean} 替换 identity / wallet RPC 客户端避免启动外部服务。
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
class WithdrawSubmitIntegrationTests {

    private static final long USER_ID = 70000101L;
    private static final long ACCOUNT_ID = 70000201L;
    private static final long WHITELIST_ID_ERC20 = 70000301L;
    private static final String ERC20_ADDRESS = "0xAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaa";

    @Autowired
    private WithdrawSubmitApplicationService submitService;

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
                new BigDecimal("1000.00000000"),
                new BigDecimal("0.00000000"),
                new BigDecimal("0.00000000"));
        Mockito.reset(kycLevelQueryClient, whitelistQueryClient, depositAddressQueryClient);
        Mockito.when(kycLevelQueryClient.queryKycLevel(USER_ID)).thenReturn(1);
        Mockito.when(whitelistQueryClient.findById(WHITELIST_ID_ERC20))
                .thenReturn(Optional.of(activeWhitelist(WHITELIST_ID_ERC20, USER_ID, "ERC20", ERC20_ADDRESS)));
        // STAGE-6-KYC trigger 2：默认所有 ERC20 测试地址都在历史 from_address 集合中，让正向路径通过；
        // 陌生地址负向用例在用例内单独 stub 覆盖。
        Mockito.when(depositAddressQueryClient.listConfirmedFromAddresses(USER_ID, "ETH"))
                .thenReturn(List.of(ERC20_ADDRESS));
    }

    /** TC-WD-001：正常提交 → COOLING + frozen += amount + ledger biz_type=WITHDRAW_FREEZE(13)。 */
    @Test
    void shouldFreezeBalanceAndCreateCoolingOrderOnNormalSubmit() {
        SubmitWithdrawCommand command = new SubmitWithdrawCommand(
                USER_ID, new BigDecimal("100.50000000"), "USDT", "ERC20",
                ERC20_ADDRESS, WHITELIST_ID_ERC20, "idem-tc-wd-001"
        );

        TradingWithdrawOrder order = submitService.submit(command);

        Assertions.assertEquals(TradingWithdrawOrderStatus.COOLING, order.status());
        Assertions.assertNotNull(order.coolingUntil());
        Assertions.assertEquals(1, supportMapper.countWithdrawOrderByUserId(USER_ID));
        // ledger biz_type=13 = WITHDRAW_FREEZE
        Assertions.assertEquals(1, supportMapper.countLedgerByUserIdAndBizType(USER_ID, 13));
        // frozen = 100.50000000
        Assertions.assertEquals(0, new BigDecimal(supportMapper.selectAccountFrozenByUserId(USER_ID))
                .compareTo(new BigDecimal("100.50000000")));
        // balance 不变
        Assertions.assertEquals(0, new BigDecimal(supportMapper.selectAccountBalanceByUserId(USER_ID))
                .compareTo(new BigDecimal("1000.00000000")));
    }

    /** TC-WD-070 / 071：提交成功后 Outbox 落 trading.withdraw.requested + partitionKey=userId。 */
    @Test
    void shouldWriteOutboxWithdrawRequestedAfterSubmit() {
        TradingWithdrawOrder order = submitService.submit(new SubmitWithdrawCommand(
                USER_ID, new BigDecimal("250"), "USDT", "ERC20",
                ERC20_ADDRESS, WHITELIST_ID_ERC20, "idem-tc-wd-070"));

        Assertions.assertEquals(1, supportMapper.countOutboxByEventTypeAndPartitionKey(
                "trading.withdraw.requested", String.valueOf(USER_ID)));
        String payload = supportMapper.selectLatestOutboxPayloadByEventType("trading.withdraw.requested");
        Assertions.assertNotNull(payload);
        Assertions.assertTrue(payload.matches("(?s).*\"withdrawId\"\\s*:\\s*" + order.id() + ".*"),
                "payload should reference withdrawId, got: " + payload);
        Assertions.assertTrue(payload.matches("(?s).*\"userId\"\\s*:\\s*" + USER_ID + ".*"));
        Assertions.assertTrue(payload.matches("(?s).*\"network\"\\s*:\\s*\"ERC20\".*"));
    }

    /** TC-WD-002：KYC 未通过 → 30043 + 无落库。 */
    @Test
    void shouldRejectWhenKycLevelInsufficient() {
        Mockito.when(kycLevelQueryClient.queryKycLevel(USER_ID)).thenReturn(0);
        SubmitWithdrawCommand command = new SubmitWithdrawCommand(
                USER_ID, new BigDecimal("100"), "USDT", "ERC20",
                ERC20_ADDRESS, WHITELIST_ID_ERC20, "idem-tc-wd-002"
        );

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> submitService.submit(command));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_KYC_REQUIRED, ex.getErrorCode());
        Assertions.assertEquals(0, supportMapper.countWithdrawOrderByUserId(USER_ID));
        Assertions.assertEquals(0, supportMapper.countLedgerByUserIdAndBizType(USER_ID, 13));
    }

    /** TC-WD-003：余额不足 → 30054。 */
    @Test
    void shouldRejectWhenBalanceInsufficient() {
        // Account 余额 1000；尝试出 1500 触发 30054
        SubmitWithdrawCommand command = new SubmitWithdrawCommand(
                USER_ID, new BigDecimal("1500"), "USDT", "ERC20",
                ERC20_ADDRESS, WHITELIST_ID_ERC20, "idem-tc-wd-003"
        );

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> submitService.submit(command));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_BALANCE_INSUFFICIENT, ex.getErrorCode());
        Assertions.assertEquals(0, supportMapper.countWithdrawOrderByUserId(USER_ID));
    }

    /** TC-WD-004：单笔超限 → 30041（>$10K）。 */
    @Test
    void shouldRejectWhenAmountExceedsSingleLimit() {
        SubmitWithdrawCommand command = new SubmitWithdrawCommand(
                USER_ID, new BigDecimal("10001"), "USDT", "ERC20",
                ERC20_ADDRESS, WHITELIST_ID_ERC20, "idem-tc-wd-004"
        );

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> submitService.submit(command));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_AMOUNT_EXCEEDS_SINGLE_LIMIT, ex.getErrorCode());
    }

    /** TC-WD-005：单日累计超限 → 30042（首次写入 $9000，第二次 $9000 提交 $9000，超 $30K 触发）。 */
    @Test
    void shouldRejectWhenDailyAggregateExceedsLimit() {
        // 先成功两次 $9000 → 当日累计 18000，再次提交 $9000 累计变 27000 仍 < 30000；
        // 改为先两次 $9000（18000），第三次提交 $12001 → 30041 单笔超限不先触发，使用 $9000 第四次累计=27000+9000=36000 ≥ 30K 但需要余额足够。
        // 充值账户到 50000 避免余额阻碍
        supportMapper.deleteAccountByUserIdAndCurrency(USER_ID, "USDT");
        supportMapper.insertSeedAccount(ACCOUNT_ID, USER_ID, "USDT",
                new BigDecimal("50000.00000000"),
                BigDecimal.ZERO, BigDecimal.ZERO);

        submitService.submit(new SubmitWithdrawCommand(USER_ID, new BigDecimal("9000"),
                "USDT", "ERC20", ERC20_ADDRESS, WHITELIST_ID_ERC20, "idem-tc-wd-005-a"));
        submitService.submit(new SubmitWithdrawCommand(USER_ID, new BigDecimal("9000"),
                "USDT", "ERC20", ERC20_ADDRESS, WHITELIST_ID_ERC20, "idem-tc-wd-005-b"));
        submitService.submit(new SubmitWithdrawCommand(USER_ID, new BigDecimal("9000"),
                "USDT", "ERC20", ERC20_ADDRESS, WHITELIST_ID_ERC20, "idem-tc-wd-005-c"));
        // 累计 27000；第四次 +5000 = 32000 ≥ 30000
        SubmitWithdrawCommand exceed = new SubmitWithdrawCommand(USER_ID, new BigDecimal("5000"),
                "USDT", "ERC20", ERC20_ADDRESS, WHITELIST_ID_ERC20, "idem-tc-wd-005-d");

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> submitService.submit(exceed));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_AMOUNT_EXCEEDS_DAILY_LIMIT, ex.getErrorCode());
    }

    /** TC-WD-011：重复幂等键 → 返回原 withdrawId，frozen 仅 +1 次。 */
    @Test
    void shouldReturnExistingOrderOnDuplicateIdempotencyKey() {
        SubmitWithdrawCommand cmd1 = new SubmitWithdrawCommand(USER_ID, new BigDecimal("100"),
                "USDT", "ERC20", ERC20_ADDRESS, WHITELIST_ID_ERC20, "idem-tc-wd-011");
        TradingWithdrawOrder first = submitService.submit(cmd1);
        TradingWithdrawOrder second = submitService.submit(cmd1);

        Assertions.assertEquals(first.id(), second.id());
        Assertions.assertEquals(1, supportMapper.countWithdrawOrderByUserId(USER_ID));
        Assertions.assertEquals(0, new BigDecimal(supportMapper.selectAccountFrozenByUserId(USER_ID))
                .compareTo(new BigDecimal("100.00000000")));
    }

    /** TC-WD-008：ERC20 地址格式非法 → 30045。 */
    @Test
    void shouldRejectWhenErc20AddressInvalid() {
        SubmitWithdrawCommand command = new SubmitWithdrawCommand(
                USER_ID, new BigDecimal("100"), "USDT", "ERC20",
                "0xinvalid", WHITELIST_ID_ERC20, "idem-tc-wd-008"
        );

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> submitService.submit(command));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_ADDRESS_INVALID, ex.getErrorCode());
    }

    /** TC-WD-009：地址不匹配白名单 → 30046（whitelist.address 与请求不符）。 */
    @Test
    void shouldRejectWhenAddressDoesNotMatchWhitelist() {
        Mockito.when(whitelistQueryClient.findById(WHITELIST_ID_ERC20))
                .thenReturn(Optional.of(activeWhitelist(WHITELIST_ID_ERC20, USER_ID, "ERC20",
                        "0xBbbbbbbbbbbbbbbbbbbbBbbbbbbbbbbbbbbbBbbb")));
        SubmitWithdrawCommand command = new SubmitWithdrawCommand(
                USER_ID, new BigDecimal("100"), "USDT", "ERC20",
                ERC20_ADDRESS, WHITELIST_ID_ERC20, "idem-tc-wd-009"
        );

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> submitService.submit(command));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_ADDRESS_NOT_WHITELISTED, ex.getErrorCode());
    }

    /**
     * STAGE-6-KYC trigger 2 TC-WD-012a：用户历史无任何入金 → 出金任何地址都触发 30056。
     */
    @Test
    void shouldRejectWhenNoDepositHistory() {
        Mockito.when(depositAddressQueryClient.listConfirmedFromAddresses(USER_ID, "ETH"))
                .thenReturn(List.of());
        SubmitWithdrawCommand command = new SubmitWithdrawCommand(
                USER_ID, new BigDecimal("100"), "USDT", "ERC20",
                ERC20_ADDRESS, WHITELIST_ID_ERC20, "idem-tc-wd-012a"
        );

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> submitService.submit(command));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_UNFAMILIAR_ADDRESS, ex.getErrorCode());
        Assertions.assertEquals(0, supportMapper.countWithdrawOrderByUserId(USER_ID));
        Assertions.assertEquals(0, supportMapper.countLedgerByUserIdAndBizType(USER_ID, 13));
    }

    /**
     * STAGE-6-KYC trigger 2 TC-WD-012b：用户有入金历史但目标地址不在集合中 → 30056。
     * 同时验证 equalsIgnoreCase 容错对 ERC20 大小写不敏感生效。
     */
    @Test
    void shouldRejectWhenTargetAddressNotInDepositHistory() {
        Mockito.when(depositAddressQueryClient.listConfirmedFromAddresses(USER_ID, "ETH"))
                .thenReturn(List.of("0xCccccccccccccccccccccccccccccccccccccccc"));
        SubmitWithdrawCommand command = new SubmitWithdrawCommand(
                USER_ID, new BigDecimal("100"), "USDT", "ERC20",
                ERC20_ADDRESS, WHITELIST_ID_ERC20, "idem-tc-wd-012b"
        );

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> submitService.submit(command));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_UNFAMILIAR_ADDRESS, ex.getErrorCode());
        Assertions.assertEquals(0, supportMapper.countWithdrawOrderByUserId(USER_ID));
    }

    /**
     * STAGE-6-KYC trigger 2 TC-WD-012c：ERC20 大小写差异不影响匹配（lowercase 历史 + mixed-case 提交）。
     */
    @Test
    void shouldAcceptCaseInsensitiveErc20Match() {
        Mockito.when(depositAddressQueryClient.listConfirmedFromAddresses(USER_ID, "ETH"))
                .thenReturn(List.of(ERC20_ADDRESS.toLowerCase()));
        SubmitWithdrawCommand command = new SubmitWithdrawCommand(
                USER_ID, new BigDecimal("100"), "USDT", "ERC20",
                ERC20_ADDRESS, WHITELIST_ID_ERC20, "idem-tc-wd-012c"
        );

        TradingWithdrawOrder order = submitService.submit(command);
        Assertions.assertEquals(TradingWithdrawOrderStatus.COOLING, order.status());
    }

    /** TC-WD-010：白名单仍处 PENDING（24h 冷静期未过）→ 30055。 */
    @Test
    void shouldRejectWhenWhitelistStillInCoolingPeriod() {
        Mockito.when(whitelistQueryClient.findById(WHITELIST_ID_ERC20))
                .thenReturn(Optional.of(new WithdrawWhitelistQueryClient.WhitelistView(
                        WHITELIST_ID_ERC20, USER_ID, "ERC20", ERC20_ADDRESS,
                        null, "PENDING", null, OffsetDateTime.now())));
        SubmitWithdrawCommand command = new SubmitWithdrawCommand(
                USER_ID, new BigDecimal("100"), "USDT", "ERC20",
                ERC20_ADDRESS, WHITELIST_ID_ERC20, "idem-tc-wd-010"
        );

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> submitService.submit(command));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_WHITELIST_COOLING_NOT_PASSED, ex.getErrorCode());
    }

    private static WithdrawWhitelistQueryClient.WhitelistView activeWhitelist(long id, long userId,
                                                                               String network, String address) {
        return new WithdrawWhitelistQueryClient.WhitelistView(
                id, userId, network, address, null, "ACTIVE",
                OffsetDateTime.now().minusHours(48),
                OffsetDateTime.now().minusHours(48));
    }
}
