package com.falconx.wallet;

import com.falconx.wallet.application.WalletAddressAllocationApplicationService;
import com.falconx.wallet.consumer.IdentityUserRegisteredEventConsumer;
import com.falconx.wallet.entity.WalletAddressAssignment;
import com.falconx.wallet.entity.WalletAddressProvisionDlqEntry;
import com.falconx.wallet.entity.WalletAddressProvisionDlqStatus;
import com.falconx.wallet.error.WalletBusinessException;
import com.falconx.wallet.error.WalletErrorCode;
import com.falconx.wallet.repository.WalletAddressProvisionDlqRepository;
import com.falconx.wallet.repository.mapper.test.WalletTestSupportMapper;
import java.util.List;
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
 * STAGE-5-WALLET-PROVISION R6：{@link IdentityUserRegisteredEventConsumer} 业务 IT。
 *
 * <p>覆盖 TC-WP-010 ~ TC-WP-015：consumer 直接调用 + mock 派生服务路径；
 * 通过 mock 验证 consumer 层路由行为（成功 / 幂等 / 坏消息 / 非法 userId / ALLOCATION_FAILED 落 DLQ / 其他异常 rethrow）。
 *
 * <p>真派生地址（含真实 xpub + 数据库写入）的成功路径归 TC-E2E-WP-001 整链 E2E 覆盖。
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
class IdentityUserRegisteredEventConsumerIntegrationTests {

    private static final long USER_ID = 80060101L;

    @Autowired
    private IdentityUserRegisteredEventConsumer consumer;

    @Autowired
    private WalletAddressProvisionDlqRepository dlqRepository;

    @Autowired
    private WalletTestSupportMapper supportMapper;

    @MockitoBean
    private WalletAddressAllocationApplicationService allocationService;

    @BeforeEach
    void clean() {
        supportMapper.clearOwnerTables();
        Mockito.reset(allocationService);
    }

    /** TC-WP-010 正常消费 → 调用 ensureDefaultUsdtDepositAddresses，返回 ≥1 个地址。 */
    @Test
    void shouldInvokeAllocationServiceOnValidPayload() {
        Mockito.when(allocationService.ensureDefaultUsdtDepositAddresses(USER_ID))
                .thenReturn(List.of(
                        Mockito.mock(WalletAddressAssignment.class),
                        Mockito.mock(WalletAddressAssignment.class)
                ));

        String payload = """
                {"eventId":"user-registered-%d","eventType":"identity.user.registered","userId":%d,"uid":"u-010","email":"wp-010@example.com","registeredAt":"2026-05-15T03:08:00Z"}
                """.formatted(USER_ID, USER_ID);

        consumer.onUserRegistered(payload);

        Mockito.verify(allocationService, Mockito.times(1)).ensureDefaultUsdtDepositAddresses(USER_ID);
        // 成功路径不应写 DLQ
        Assertions.assertEquals(0, dlqRepository.countFiltered(null, USER_ID));
    }

    /** TC-WP-011 幂等性：重复消费同 eventId / userId → consumer 每次都调 allocationService（由其内部唯一约束保证地址不重复）。 */
    @Test
    void shouldInvokeAllocationServiceOnEveryDuplicateEventWithoutOwnDedup() {
        Mockito.when(allocationService.ensureDefaultUsdtDepositAddresses(USER_ID))
                .thenReturn(List.of(Mockito.mock(WalletAddressAssignment.class)));

        String payload = """
                {"eventId":"user-registered-%d","eventType":"identity.user.registered","userId":%d,"uid":"u-011","email":"wp-011@example.com","registeredAt":"2026-05-15T03:08:00Z"}
                """.formatted(USER_ID, USER_ID);

        consumer.onUserRegistered(payload);
        consumer.onUserRegistered(payload);
        consumer.onUserRegistered(payload);

        // consumer 不自己去重；真正去重在 ensureDefaultUsdtDepositAddresses 内部基于
        // t_wallet_address 唯一约束完成（详见 STAGE-5-WALLET-PROVISION-test-cases.md §4 注）
        Mockito.verify(allocationService, Mockito.times(3)).ensureDefaultUsdtDepositAddresses(USER_ID);
        Assertions.assertEquals(0, dlqRepository.countFiltered(null, USER_ID));
    }

    /** TC-WP-012 坏消息（payload 解析失败）→ 不抛、不重试、不阻塞、不调 allocationService。 */
    @Test
    void shouldSkipMalformedPayloadWithoutInvokingAllocation() {
        // 非合法 JSON
        Assertions.assertDoesNotThrow(() -> consumer.onUserRegistered("not-a-json-string"),
                "坏消息不得抛异常（避免 Spring Kafka 重试堵塞 partition）");

        Mockito.verifyNoInteractions(allocationService);
        Assertions.assertEquals(0, dlqRepository.countFiltered(null, null));
    }

    /** TC-WP-013 userId<=0 → skip + 不调 allocationService + 不写 DLQ。 */
    @Test
    void shouldSkipInvalidUserIdWithoutInvokingAllocation() {
        // userId=0
        consumer.onUserRegistered("{\"eventId\":\"user-registered-0\",\"userId\":0,\"uid\":\"u-013\",\"email\":\"wp-013@example.com\"}");
        // userId 缺失
        consumer.onUserRegistered("{\"eventId\":\"user-registered-na\",\"uid\":\"u-013\",\"email\":\"wp-013@example.com\"}");

        Mockito.verifyNoInteractions(allocationService);
        Assertions.assertEquals(0, dlqRepository.countFiltered(null, null));
    }

    /** TC-WP-014 WALLET_ADDRESS_ALLOCATION_FAILED → 落 DLQ status=PENDING + 吞掉（不抛、不重试）。 */
    @Test
    void shouldEnqueueDlqOnAllocationFailedAndSwallow() {
        Mockito.when(allocationService.ensureDefaultUsdtDepositAddresses(USER_ID))
                .thenThrow(new WalletBusinessException(WalletErrorCode.WALLET_ADDRESS_ALLOCATION_FAILED));

        String eventId = "user-registered-" + USER_ID;
        String payload = """
                {"eventId":"%s","eventType":"identity.user.registered","userId":%d,"uid":"u-014","email":"wp-014@example.com","registeredAt":"2026-05-15T03:08:00Z"}
                """.formatted(eventId, USER_ID);

        Assertions.assertDoesNotThrow(() -> consumer.onUserRegistered(payload),
                "ALLOCATION_FAILED 必须被 consumer 吞掉，不抛异常（避免 Kafka 重试堵塞 partition）");

        WalletAddressProvisionDlqEntry entry = dlqRepository.findByEventId(eventId).orElseThrow(() ->
                new AssertionError("DLQ 表必须新增一行 event_id=" + eventId));
        Assertions.assertEquals(USER_ID, entry.userId().longValue());
        Assertions.assertEquals("u-014", entry.uid());
        Assertions.assertEquals("wp-014@example.com", entry.email());
        Assertions.assertEquals(WalletAddressProvisionDlqStatus.PENDING, entry.status());
        Assertions.assertEquals(WalletErrorCode.WALLET_ADDRESS_ALLOCATION_FAILED.code(), entry.lastErrorCode(),
                "lastErrorCode 必须为 WALLET_ADDRESS_ALLOCATION_FAILED 错误码 (20006)");
        Assertions.assertNotNull(entry.lastErrorMessage(),
                "lastErrorMessage 必须非空（从 WalletBusinessException.getMessage 取）");
        Assertions.assertEquals(1, entry.attemptCount());
        Assertions.assertNull(entry.resolvedAt());
    }

    /** TC-WP-015 其他 RuntimeException → consumer rethrow（走 Spring Kafka 默认重试 + DLT）；不入 DLQ。 */
    @Test
    void shouldRethrowOnOtherRuntimeExceptionsAndNotEnqueueDlq() {
        Mockito.when(allocationService.ensureDefaultUsdtDepositAddresses(USER_ID))
                .thenThrow(new RuntimeException("RPC timeout"));

        String eventId = "user-registered-" + USER_ID;
        String payload = """
                {"eventId":"%s","eventType":"identity.user.registered","userId":%d,"uid":"u-015","email":"wp-015@example.com"}
                """.formatted(eventId, USER_ID);

        Assertions.assertThrows(RuntimeException.class, () -> consumer.onUserRegistered(payload),
                "非 ALLOCATION_FAILED 的 RuntimeException 必须 rethrow（让 Spring Kafka 走默认重试 + DLT）");

        Assertions.assertEquals(0, dlqRepository.countFiltered(null, USER_ID),
                "非 ALLOCATION_FAILED 异常不入业务 DLQ（由 Spring Kafka DLT 兜底）");
    }
}
