package com.falconx.wallet;

import com.falconx.wallet.application.WalletAddressAllocationApplicationService;
import com.falconx.wallet.application.WalletAddressProvisionAdminApplicationService;
import com.falconx.wallet.entity.WalletAddressAssignment;
import com.falconx.wallet.entity.WalletAddressProvisionDlqEntry;
import com.falconx.wallet.entity.WalletAddressProvisionDlqStatus;
import com.falconx.wallet.error.WalletBusinessException;
import com.falconx.wallet.error.WalletErrorCode;
import com.falconx.wallet.repository.WalletAddressProvisionDlqRepository;
import com.falconx.wallet.repository.mapper.test.WalletTestSupportMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * STAGE-5-WALLET-PROVISION Phase 2 R6：DLQ admin 应用层 IT。
 *
 * <p>覆盖 TC-WP-030 ~ TC-WP-034：list（分页 + 字段）+ list 过滤（status / userId）+
 * retry 失败 90860（id 不存在）+ retry 失败 90861（已 RESOLVED）+ retry 成功（调 allocation
 * + markResolved + 返回 RESOLVED）。
 *
 * <p>HTTP filter 层（X-Internal-Token / X-Admin-User-Id）已由 STAGE-2-DEPOSIT R7 联调验证；
 * 错误码翻译由 console-service AdminWalletProvisionEndpointIntegrationTests 覆盖。
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
class WalletAddressProvisionAdminApplicationServiceIntegrationTests {

    private static final long USER_ID_A = 80070101L;
    private static final long USER_ID_B = 80070102L;
    private static final long ADMIN_ID = 80079001L;

    @Autowired
    private WalletAddressProvisionAdminApplicationService adminService;

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

    /** TC-WP-030 list 返回字段完整（id / eventId / userId / status / lastErrorCode 等）。 */
    @Test
    void shouldListReturnFullFields() {
        seedPendingEntry(USER_ID_A, "user-registered-030", "030-uid", "030@example.com");

        List<WalletAddressProvisionDlqEntry> items = adminService.list(null, null, 1, 20);
        Assertions.assertEquals(1, items.size());
        WalletAddressProvisionDlqEntry e = items.get(0);
        Assertions.assertNotNull(e.id());
        Assertions.assertEquals("user-registered-030", e.eventId());
        Assertions.assertEquals(USER_ID_A, e.userId().longValue());
        Assertions.assertEquals("030-uid", e.uid());
        Assertions.assertEquals("030@example.com", e.email());
        Assertions.assertEquals(WalletAddressProvisionDlqStatus.PENDING, e.status());
        Assertions.assertEquals("20007", e.lastErrorCode());
        Assertions.assertNotNull(e.lastErrorMessage());
        Assertions.assertNotNull(e.createdAt());
        Assertions.assertNotNull(e.lastAttemptAt());
        Assertions.assertNull(e.resolvedAt());

        Assertions.assertEquals(1L, adminService.count(null, null));
    }

    /** TC-WP-031 list 按 status / userId 过滤口径与 countFiltered 一致；分页 page/size 边界保护。 */
    @Test
    void shouldListFilterByStatusAndUserIdWithPaginationGuards() {
        // 种 3 PENDING (A) + 1 RESOLVED (A) + 1 PENDING (B)
        seedPendingEntry(USER_ID_A, "user-registered-031-a-1", null, null);
        seedPendingEntry(USER_ID_A, "user-registered-031-a-2", null, null);
        seedPendingEntry(USER_ID_A, "user-registered-031-a-3", null, null);
        long resolvedId = seedPendingEntry(USER_ID_A, "user-registered-031-a-resolved", null, null);
        Assertions.assertTrue(dlqRepository.markResolved(resolvedId));
        seedPendingEntry(USER_ID_B, "user-registered-031-b-1", null, null);

        Assertions.assertEquals(4, adminService.list(0, null, 1, 20).size(), "PENDING 应返回 4 条");
        Assertions.assertEquals(1, adminService.list(1, null, 1, 20).size(), "RESOLVED 应返回 1 条");
        Assertions.assertEquals(3, adminService.list(0, USER_ID_A, 1, 20).size(),
                "PENDING + USER_A 应返回 3 条");
        Assertions.assertEquals(1, adminService.list(0, USER_ID_B, 1, 20).size(),
                "PENDING + USER_B 应返回 1 条");

        // page<1 → 提升到 1（PENDING 4 条全返回）；size<1 → 提升到 1（只取首条）
        Assertions.assertEquals(4, adminService.list(0, null, 0, 20).size(), "page<1 应保护为 1");
        Assertions.assertEquals(1, adminService.list(0, null, 1, 0).size(), "size<1 应保护为 1（取首条）");
        // 实际分页：5 条 总数；page=1 size=2 → 2 条；page=2 size=2 → 2 条；page=3 size=2 → 1 条
        Assertions.assertEquals(2, adminService.list(null, null, 1, 2).size());
        Assertions.assertEquals(2, adminService.list(null, null, 2, 2).size());
        Assertions.assertEquals(1, adminService.list(null, null, 3, 2).size());

        Assertions.assertEquals(5L, adminService.count(null, null));
        Assertions.assertEquals(4L, adminService.count(0, null));
        Assertions.assertEquals(1L, adminService.count(1, null));
    }

    /** TC-WP-032 retry id 不存在 → throw WALLET_ADDRESS_DLQ_NOT_FOUND（90860）。 */
    @Test
    void shouldThrowDlqNotFoundOnUnknownId() {
        WalletBusinessException ex = Assertions.assertThrows(WalletBusinessException.class,
                () -> adminService.retry(99999999L, ADMIN_ID, "xpub 已补齐"));
        Assertions.assertEquals(WalletErrorCode.WALLET_ADDRESS_DLQ_NOT_FOUND, ex.getErrorCode());
        Mockito.verifyNoInteractions(allocationService);
    }

    /** TC-WP-033 retry 已 RESOLVED → throw WALLET_ADDRESS_DLQ_ALREADY_RESOLVED（90861）。 */
    @Test
    void shouldThrowAlreadyResolvedOnResolvedEntry() {
        long id = seedPendingEntry(USER_ID_A, "user-registered-033", null, null);
        Assertions.assertTrue(dlqRepository.markResolved(id));

        WalletBusinessException ex = Assertions.assertThrows(WalletBusinessException.class,
                () -> adminService.retry(id, ADMIN_ID, "xpub 已补齐"));
        Assertions.assertEquals(WalletErrorCode.WALLET_ADDRESS_DLQ_ALREADY_RESOLVED, ex.getErrorCode());
        Mockito.verifyNoInteractions(allocationService);
    }

    /** TC-WP-034 retry 成功 → 调 ensureDefaultUsdtDepositAddresses + markResolved + 返回 RESOLVED entry。 */
    @Test
    void shouldRetrySuccessfullyAllocateAndMarkResolved() {
        long id = seedPendingEntry(USER_ID_A, "user-registered-034", "034-uid", "034@example.com");
        Mockito.when(allocationService.ensureDefaultUsdtDepositAddresses(USER_ID_A))
                .thenReturn(List.of(
                        Mockito.mock(WalletAddressAssignment.class),
                        Mockito.mock(WalletAddressAssignment.class)));

        WalletAddressProvisionDlqEntry result = adminService.retry(id, ADMIN_ID, "xpub 已补齐");

        Mockito.verify(allocationService, Mockito.times(1)).ensureDefaultUsdtDepositAddresses(USER_ID_A);
        Assertions.assertEquals(WalletAddressProvisionDlqStatus.RESOLVED, result.status());
        Assertions.assertNotNull(result.resolvedAt(), "resolvedAt 必须填充");

        WalletAddressProvisionDlqEntry persisted = dlqRepository.findById(id).orElseThrow();
        Assertions.assertEquals(WalletAddressProvisionDlqStatus.RESOLVED, persisted.status());
        Assertions.assertNotNull(persisted.resolvedAt());
    }

    private long seedPendingEntry(long userId, String eventId, String uid, String email) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        dlqRepository.recordFailure(new WalletAddressProvisionDlqEntry(
                null, eventId, userId, uid, email,
                1, WalletAddressProvisionDlqStatus.PENDING,
                "20007", "xpub for chain ETH is missing",
                now, null, now));
        return dlqRepository.findByEventId(eventId).orElseThrow().id();
    }
}
