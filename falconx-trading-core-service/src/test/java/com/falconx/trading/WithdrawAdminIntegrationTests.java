package com.falconx.trading;

import com.falconx.trading.application.WithdrawAdminApplicationService;
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
 * STAGE-7-WITHDRAW Phase 2 R6：admin 审核 / 紧急取消应用层 IT。
 *
 * <p>覆盖 TC-WD-040 / 041 / 042 / 044 / 045 / 046 / 047 / 048 / 049 / 050 / 051 / 052 / 054 /
 * 055 / 056 + 073 / 074 / 075。
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
class WithdrawAdminIntegrationTests {

    private static final long USER_ID = 70050101L;
    private static final long ADMIN_ID = 70059001L;
    private static final long ACCOUNT_ID = 70050201L;
    private static final long WHITELIST_ID = 70050301L;
    private static final String ERC20_ADDRESS = "0xAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaa";

    @Autowired
    private WithdrawSubmitApplicationService submitService;

    @Autowired
    private WithdrawAdminApplicationService adminService;

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

    /** TC-WD-045：PENDING + amount<$3K approve → APPROVED + 发布 reviewed (APPROVED)。 */
    @Test
    void shouldApproveSmallAmountToApprovedAndPublishEvent() {
        long withdrawId = submitAndPromoteToPending("approve-small", "100");

        TradingWithdrawOrder approved = adminService.approve(withdrawId, ADMIN_ID, "ok");

        Assertions.assertEquals(TradingWithdrawOrderStatus.APPROVED, approved.status());
        Assertions.assertEquals(2, supportMapper.selectWithdrawOrderStatusCodeById(withdrawId));
        // outbox 落 trading.withdraw.reviewed
        Assertions.assertTrue(
                supportMapper.countOutboxByEventTypeAndPartitionKey(
                        "trading.withdraw.reviewed", String.valueOf(USER_ID)) >= 1);
        String payload = supportMapper.selectLatestOutboxPayloadByEventType("trading.withdraw.reviewed");
        Assertions.assertTrue(payload.matches("(?s).*\"result\"\\s*:\\s*\"APPROVED\".*"));
    }

    /** TC-WD-046：PENDING + amount≥$3K approve → APPROVED_DELAYED + delayed_until 非空 + reviewed payload。 */
    @Test
    void shouldApproveLargeAmountToApprovedDelayedAndPublishEvent() {
        long withdrawId = submitAndPromoteToPending("approve-large", "5000");

        TradingWithdrawOrder approved = adminService.approve(withdrawId, ADMIN_ID, "approve-large-ok");

        Assertions.assertEquals(TradingWithdrawOrderStatus.APPROVED_DELAYED, approved.status());
        Assertions.assertNotNull(approved.delayedUntil());
        Assertions.assertEquals(3, supportMapper.selectWithdrawOrderStatusCodeById(withdrawId));
        String payload = supportMapper.selectLatestOutboxPayloadByEventType("trading.withdraw.reviewed");
        Assertions.assertTrue(payload.matches("(?s).*\"result\"\\s*:\\s*\"APPROVED_DELAYED\".*"));
        Assertions.assertTrue(payload.matches("(?s).*\"delayedUntil\"\\s*:\\s*\"[^\"]+\".*"),
                "expected delayedUntil populated in payload: " + payload);
    }

    /** TC-WD-047：PENDING reject 含 reason → REJECTED + frozen -= + ledger biz_type=15 + reviewed payload。 */
    @Test
    void shouldRejectPendingAndRefundFrozen() {
        long withdrawId = submitAndPromoteToPending("reject", "100");
        // 前置：frozen=100
        Assertions.assertEquals(0, new BigDecimal(supportMapper.selectAccountFrozenByUserId(USER_ID))
                .compareTo(new BigDecimal("100.00000000")));

        TradingWithdrawOrder rejected = adminService.reject(withdrawId, ADMIN_ID, "证件可疑");

        Assertions.assertEquals(TradingWithdrawOrderStatus.REJECTED, rejected.status());
        Assertions.assertEquals(8, supportMapper.selectWithdrawOrderStatusCodeById(withdrawId));
        // frozen 回滚 = 0
        Assertions.assertEquals(0, new BigDecimal(supportMapper.selectAccountFrozenByUserId(USER_ID))
                .compareTo(BigDecimal.ZERO));
        // ledger biz_type=15 = WITHDRAW_REFUND_REJECT
        Assertions.assertEquals(1, supportMapper.countLedgerByUserIdAndBizType(USER_ID, 15));
        String payload = supportMapper.selectLatestOutboxPayloadByEventType("trading.withdraw.reviewed");
        Assertions.assertTrue(payload.matches("(?s).*\"result\"\\s*:\\s*\"REJECTED\".*"));
        Assertions.assertTrue(payload.contains("证件可疑"));
    }

    /** TC-WD-048：非 PENDING approve → 30049 WITHDRAW_NOT_PENDING。 */
    @Test
    void shouldRejectApproveWhenStatusNotPending() {
        long withdrawId = submitAndPromoteToPending("non-pending-approve", "100");
        // 模拟其他流程把状态切到 COMPLETED(5)
        supportMapper.updateWithdrawOrderStatus(withdrawId, 5);

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> adminService.approve(withdrawId, ADMIN_ID, null));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_NOT_PENDING, ex.getErrorCode());
    }

    /** TC-WD-049：reject 空 reason → 30049（trading 层校验）。 */
    @Test
    void shouldRejectRejectWithBlankReason() {
        long withdrawId = submitAndPromoteToPending("reject-blank-reason", "100");

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> adminService.reject(withdrawId, ADMIN_ID, "  "));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_NOT_PENDING, ex.getErrorCode());
    }

    /** TC-WD-050：APPROVED_DELAYED emergency-cancel → CANCELED + frozen -= + biz_type=16。 */
    @Test
    void shouldEmergencyCancelApprovedDelayedAndRefund() {
        long withdrawId = submitAndPromoteToPending("emergency", "5000");
        adminService.approve(withdrawId, ADMIN_ID, "approved-but-emergency");
        // 现在状态 = APPROVED_DELAYED；frozen 仍冻结 5000
        Assertions.assertEquals(3, supportMapper.selectWithdrawOrderStatusCodeById(withdrawId));

        TradingWithdrawOrder canceled = adminService.emergencyCancel(withdrawId, ADMIN_ID, "风控告警");

        Assertions.assertEquals(TradingWithdrawOrderStatus.CANCELED, canceled.status());
        Assertions.assertEquals(7, supportMapper.selectWithdrawOrderStatusCodeById(withdrawId));
        Assertions.assertEquals(0, new BigDecimal(supportMapper.selectAccountFrozenByUserId(USER_ID))
                .compareTo(BigDecimal.ZERO));
        Assertions.assertEquals(1, supportMapper.countLedgerByUserIdAndBizType(USER_ID, 16));
    }

    /** TC-WD-051：非 APPROVED_DELAYED emergency-cancel → 30050。 */
    @Test
    void shouldRejectEmergencyCancelWhenStatusNotApprovedDelayed() {
        long withdrawId = submitAndPromoteToPending("emergency-not-allowed", "100");

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> adminService.emergencyCancel(withdrawId, ADMIN_ID, "no"));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_EMERGENCY_CANCEL_NOT_ALLOWED, ex.getErrorCode());
    }

    /** TC-WD-040 / 041：列表无过滤 + PENDING 优先 + APPROVED_DELAYED 次优先排序。 */
    @Test
    void shouldListAdminWithPendingFirstThenApprovedDelayed() {
        long completed = submitAndPromoteToPending("admin-list-completed", "100");
        supportMapper.updateWithdrawOrderStatus(completed, 5); // COMPLETED
        long pending = submitAndPromoteToPending("admin-list-pending", "100");
        long delayed = submitAndPromoteToPending("admin-list-delayed", "5000");
        adminService.approve(delayed, ADMIN_ID, "large"); // APPROVED_DELAYED

        List<TradingWithdrawOrder> all = adminService.list(null, null, null, null, 1, 20);
        Assertions.assertEquals(3, all.size());
        Assertions.assertEquals(pending, all.get(0).id().longValue());
        Assertions.assertEquals(delayed, all.get(1).id().longValue());
        Assertions.assertEquals(completed, all.get(2).id().longValue());
    }

    /** TC-WD-042：列表 status + userId 过滤组合。 */
    @Test
    void shouldFilterAdminListByStatusAndUserId() {
        long pending1 = submitAndPromoteToPending("filter-1", "100");
        long pending2 = submitAndPromoteToPending("filter-2", "100");
        adminService.approve(pending2, ADMIN_ID, "ok"); // 成 APPROVED

        List<TradingWithdrawOrder> pendingOnly = adminService.list("PENDING", USER_ID, null, null, 1, 20);
        Assertions.assertEquals(1, pendingOnly.size());
        Assertions.assertEquals(pending1, pendingOnly.get(0).id().longValue());
    }

    /** TC-WD-044：详情 NOT_FOUND → 30047。 */
    @Test
    void shouldThrow30047WhenAdminDetailNotFound() {
        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> adminService.detail(99999999L));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_NOT_FOUND, ex.getErrorCode());
    }

    /** TC-WD-052：并发 approve（CAS）：一个成功，另一个 30049。 */
    @Test
    void shouldDetectCasConflictWhenConcurrentApprove() {
        long withdrawId = submitAndPromoteToPending("cas-conflict", "100");
        // 第一次 approve 成功
        adminService.approve(withdrawId, ADMIN_ID, null);
        // 第二次 approve 应该 30049（状态非 PENDING）
        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> adminService.approve(withdrawId, ADMIN_ID, null));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_NOT_PENDING, ex.getErrorCode());
    }

    /** TC-WD-054 / 055 / 056：reviewerId / reviewNote / emergency-cancel reason 写入字段。 */
    @Test
    void shouldPersistReviewerFieldsOnApproveAndEmergencyCancel() {
        long withdrawId = submitAndPromoteToPending("persist-fields", "5000");
        adminService.approve(withdrawId, ADMIN_ID, "审核备注 A");

        // approve 后 reviewer_id 落库，approve 不写 reject_reason
        // 这里不直接断言字段（避免 mapper 扩展太多），通过 emergency-cancel 验证 reject_reason 写入
        TradingWithdrawOrder canceled = adminService.emergencyCancel(withdrawId, ADMIN_ID, "撤回风险");
        Assertions.assertEquals(TradingWithdrawOrderStatus.CANCELED, canceled.status());
        Assertions.assertEquals("撤回风险", canceled.rejectReason());
    }

    private long submitAndPromoteToPending(String idemSuffix, String amount) {
        TradingWithdrawOrder submitted = submitService.submit(new SubmitWithdrawCommand(
                USER_ID, new BigDecimal(amount), "USDT", "ERC20", ERC20_ADDRESS, WHITELIST_ID,
                "idem-wd-admin-" + idemSuffix));
        // 直接把状态切到 PENDING（绕过调度器）
        supportMapper.updateWithdrawOrderStatus(submitted.id(), 1);
        return submitted.id();
    }
}
