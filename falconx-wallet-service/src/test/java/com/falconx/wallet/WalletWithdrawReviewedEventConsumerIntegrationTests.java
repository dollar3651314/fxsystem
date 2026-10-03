package com.falconx.wallet;

import com.falconx.trading.contract.event.TradingWithdrawReviewedEventPayload;
import com.falconx.wallet.consumer.TradingWithdrawReviewedEventConsumer;
import com.falconx.wallet.repository.mapper.test.WalletTestSupportMapper;
import com.falconx.wallet.withdraw.WalletWithdrawBroadcastApplicationService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * STAGE-7-WITHDRAW Phase 3 R6：{@link TradingWithdrawReviewedEventConsumer} 业务 IT。
 *
 * <p>覆盖：
 * <ul>
 *   <li>TC-WD-110 APPROVED → broadcastService.broadcast 被调用（参数匹配 withdrawId）</li>
 *   <li>TC-WD-111 APPROVED_DELAYED → broadcastService 不被调用（delayed 由 trading-core 调度器解锁）</li>
 *   <li>TC-WD-112 REJECTED → broadcastService 不被调用</li>
 *   <li>TC-WD-113 重复 APPROVED 顺序处理：consumer 每条事件都调用一次 broadcastService，
 *       由 broadcastService 内部 uk_withdraw_order 完成实际去重（TC-WD-124 验证 DB 级效果）；
 *       本用例验证 consumer 层无去重，避免后端业务依赖前端去重错误假设</li>
 * </ul>
 */
@ActiveProfiles("stage5")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = WalletServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_wallet_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root"
        }
)
class WalletWithdrawReviewedEventConsumerIntegrationTests {

    private static final long USER_ID = 80030101L;
    private static final long WITHDRAW_ORDER_ID = 80030201L;
    private static final String TARGET_ADDRESS = "0xCcccccccCcccccccCcccccccCcccccccCcccCccc";

    @Autowired
    private TradingWithdrawReviewedEventConsumer consumer;

    @Autowired
    private WalletTestSupportMapper supportMapper;

    @MockitoBean
    private WalletWithdrawBroadcastApplicationService broadcastService;

    @BeforeEach
    void clean() {
        supportMapper.clearOwnerTables();
        Mockito.reset(broadcastService);
    }

    /** TC-WD-110：APPROVED → broadcastService.broadcast 被调用，参数 withdrawId 匹配。 */
    @Test
    void shouldDispatchToBroadcastServiceOnApproved() {
        TradingWithdrawReviewedEventPayload payload = newReviewed("APPROVED", null);

        consumer.consume("evt-reviewed-approved-001", payload);

        ArgumentCaptor<TradingWithdrawReviewedEventPayload> captor =
                ArgumentCaptor.forClass(TradingWithdrawReviewedEventPayload.class);
        Mockito.verify(broadcastService, Mockito.times(1)).broadcast(captor.capture());
        Assertions.assertEquals(WITHDRAW_ORDER_ID, captor.getValue().withdrawId());
        Assertions.assertEquals("APPROVED", captor.getValue().result());
    }

    /** TC-WD-111：APPROVED_DELAYED → broadcastService 不被调用（delayed 由 trading-core 内部调度器二次发 APPROVED）。 */
    @Test
    void shouldSkipBroadcastForApprovedDelayed() {
        TradingWithdrawReviewedEventPayload payload = newReviewed("APPROVED_DELAYED",
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(6));

        consumer.consume("evt-reviewed-delayed-001", payload);

        Mockito.verifyNoInteractions(broadcastService);
    }

    /** TC-WD-112：REJECTED → broadcastService 不被调用。 */
    @Test
    void shouldSkipBroadcastForRejected() {
        TradingWithdrawReviewedEventPayload payload = newReviewed("REJECTED", null);

        consumer.consume("evt-reviewed-rejected-001", payload);

        Mockito.verifyNoInteractions(broadcastService);
    }

    /**
     * TC-WD-113：重复 APPROVED 顺序处理 → consumer 每条事件都调用一次 broadcastService。
     *
     * <p>注：consumer 不在自身层做去重；真正的去重在 {@link WalletWithdrawBroadcastApplicationService}
     * 内 {@code findByWithdrawOrderId} + uk_withdraw_order 约束完成（TC-WD-124 用例验证 DB 级 1 行不重复）。
     * 本用例验证 consumer 行为一致，避免误以为 consumer 自带 inbox 去重。
     */
    @Test
    void shouldDispatchEveryApprovedEventToBroadcastServiceWithoutOwnDedup() {
        TradingWithdrawReviewedEventPayload payload = newReviewed("APPROVED", null);

        consumer.consume("evt-reviewed-dup-001", payload);
        consumer.consume("evt-reviewed-dup-002", payload);

        Mockito.verify(broadcastService, Mockito.times(2)).broadcast(Mockito.any());
    }

    private static TradingWithdrawReviewedEventPayload newReviewed(String result, OffsetDateTime delayedUntil) {
        return new TradingWithdrawReviewedEventPayload(
                WITHDRAW_ORDER_ID,
                USER_ID,
                result,
                999L,
                OffsetDateTime.now(ZoneOffset.UTC),
                "REJECTED".equals(result) ? "证件可疑" : null,
                delayedUntil,
                new BigDecimal("100.00000000"),
                "USDT",
                "ERC20",
                TARGET_ADDRESS
        );
    }
}
