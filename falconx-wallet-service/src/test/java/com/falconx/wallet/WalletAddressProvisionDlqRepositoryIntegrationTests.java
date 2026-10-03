package com.falconx.wallet;

import com.falconx.wallet.entity.WalletAddressProvisionDlqEntry;
import com.falconx.wallet.entity.WalletAddressProvisionDlqStatus;
import com.falconx.wallet.repository.WalletAddressProvisionDlqRepository;
import com.falconx.wallet.repository.mapper.test.WalletTestSupportMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * STAGE-5-WALLET-PROVISION Phase 2 R6：地址预分配 DLQ Repository 集成测试。
 *
 * <p>覆盖 TC-WP-020 ~ TC-WP-023：recordFailure（含 upsertOnDuplicate 增量行为）+
 * findPaginated（status / userId 过滤 + 分页）+ countFiltered + markResolved。
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = WalletServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_wallet_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root"
        }
)
class WalletAddressProvisionDlqRepositoryIntegrationTests {

    private static final long USER_ID_A = 80050101L;
    private static final long USER_ID_B = 80050102L;
    private static final String UID_A = "wpdlq-uid-a";
    private static final String UID_B = "wpdlq-uid-b";

    @Autowired
    private WalletAddressProvisionDlqRepository dlqRepository;

    @Autowired
    private WalletTestSupportMapper supportMapper;

    @BeforeEach
    void clean() {
        supportMapper.clearOwnerTables();
    }

    /** TC-WP-020 recordFailure 首次入库 → 雪花 ID 自动生成 + findByEventId 回查命中。 */
    @Test
    void shouldRecordFirstFailureAndAssignSnowflakeId() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String eventId = "user-registered-" + USER_ID_A;
        dlqRepository.recordFailure(new WalletAddressProvisionDlqEntry(
                null, eventId, USER_ID_A, UID_A, "alice@example.com",
                1, WalletAddressProvisionDlqStatus.PENDING,
                "20007", "xpub for chain ETH is missing",
                now, null, now));

        Optional<WalletAddressProvisionDlqEntry> found = dlqRepository.findByEventId(eventId);
        Assertions.assertTrue(found.isPresent(), "首次 recordFailure 后 findByEventId 必须命中");
        WalletAddressProvisionDlqEntry e = found.get();
        Assertions.assertNotNull(e.id(), "雪花 ID 必须自动生成");
        Assertions.assertTrue(e.id() > 0, "雪花 ID 必须为正数");
        Assertions.assertEquals(eventId, e.eventId());
        Assertions.assertEquals(USER_ID_A, e.userId().longValue());
        Assertions.assertEquals(UID_A, e.uid());
        Assertions.assertEquals("alice@example.com", e.email());
        Assertions.assertEquals(1, e.attemptCount());
        Assertions.assertEquals(WalletAddressProvisionDlqStatus.PENDING, e.status());
        Assertions.assertEquals("20007", e.lastErrorCode());
        Assertions.assertNull(e.resolvedAt());

        Optional<WalletAddressProvisionDlqEntry> byId = dlqRepository.findById(e.id());
        Assertions.assertTrue(byId.isPresent(), "findById 必须命中");
        Assertions.assertEquals(e.eventId(), byId.get().eventId());
    }

    /** TC-WP-020b upsertOnDuplicate：同 eventId 重复 recordFailure → attempt_count 累加 + 错误覆盖。 */
    @Test
    void shouldIncrementAttemptCountOnDuplicateEventId() {
        OffsetDateTime t1 = OffsetDateTime.now(ZoneOffset.UTC);
        String eventId = "user-registered-" + USER_ID_A;
        // 首次入库
        dlqRepository.recordFailure(new WalletAddressProvisionDlqEntry(
                null, eventId, USER_ID_A, UID_A, "alice@example.com",
                1, WalletAddressProvisionDlqStatus.PENDING,
                "20007", "first error", t1, null, t1));
        // 同 eventId 二次 recordFailure（PENDING 行存在 → upsert，不插新行）
        OffsetDateTime t2 = t1.plusSeconds(30);
        dlqRepository.recordFailure(new WalletAddressProvisionDlqEntry(
                null, eventId, USER_ID_A, UID_A, "alice@example.com",
                1, WalletAddressProvisionDlqStatus.PENDING,
                "20007", "second error", t2, null, t2));

        // 仍只有 1 行（按 event_id 唯一约束 + upsert）
        Assertions.assertEquals(1, dlqRepository.countFiltered(null, USER_ID_A));
        WalletAddressProvisionDlqEntry e = dlqRepository.findByEventId(eventId).orElseThrow();
        Assertions.assertEquals(2, e.attemptCount(), "重复 recordFailure 应使 attempt_count + 1");
        Assertions.assertEquals("second error", e.lastErrorMessage(), "lastErrorMessage 应被覆盖为最新");
    }

    /** TC-WP-021 findPaginated 分页 + status / userId 过滤 + ORDER BY created_at DESC。 */
    @Test
    void shouldPaginateAndFilterByStatusAndUserId() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // 种 3 个 USER_A 的 PENDING + 1 个 USER_A 的 RESOLVED + 1 个 USER_B 的 PENDING
        for (int i = 0; i < 3; i++) {
            String eventId = "user-registered-a-" + i;
            dlqRepository.recordFailure(new WalletAddressProvisionDlqEntry(
                    null, eventId, USER_ID_A, UID_A, "alice@example.com",
                    1, WalletAddressProvisionDlqStatus.PENDING,
                    "20007", "err " + i, now.plusSeconds(i), null, now.plusSeconds(i)));
        }
        // 第 4 个并立即标 resolved
        dlqRepository.recordFailure(new WalletAddressProvisionDlqEntry(
                null, "user-registered-a-resolved", USER_ID_A, UID_A, "alice@example.com",
                1, WalletAddressProvisionDlqStatus.PENDING,
                "20007", "old", now, null, now));
        long resolvedId = dlqRepository.findByEventId("user-registered-a-resolved").orElseThrow().id();
        Assertions.assertTrue(dlqRepository.markResolved(resolvedId), "markResolved 必须返回 true");

        dlqRepository.recordFailure(new WalletAddressProvisionDlqEntry(
                null, "user-registered-b-0", USER_ID_B, UID_B, "bob@example.com",
                1, WalletAddressProvisionDlqStatus.PENDING,
                "20007", "err b", now, null, now));

        // 状态过滤
        Assertions.assertEquals(4, dlqRepository.findPaginated(0, null, 0, 10).size(),
                "PENDING 全过滤应返回 4 条（3 USER_A + 1 USER_B）");
        Assertions.assertEquals(1, dlqRepository.findPaginated(1, null, 0, 10).size(),
                "RESOLVED 全过滤应返回 1 条");
        Assertions.assertEquals(5, dlqRepository.findPaginated(null, null, 0, 10).size(),
                "null status 应返回 5 条");

        // userId 过滤
        Assertions.assertEquals(3, dlqRepository.findPaginated(0, USER_ID_A, 0, 10).size(),
                "PENDING + USER_A 过滤应返回 3 条");
        Assertions.assertEquals(1, dlqRepository.findPaginated(0, USER_ID_B, 0, 10).size(),
                "PENDING + USER_B 过滤应返回 1 条");

        // 分页
        List<WalletAddressProvisionDlqEntry> page1 = dlqRepository.findPaginated(null, null, 0, 2);
        List<WalletAddressProvisionDlqEntry> page2 = dlqRepository.findPaginated(null, null, 2, 2);
        Assertions.assertEquals(2, page1.size());
        Assertions.assertEquals(2, page2.size());
        Assertions.assertNotEquals(page1.get(0).id(), page2.get(0).id(), "分页跨页 id 不应重复");
    }

    /** TC-WP-022 countFiltered 与 findPaginated 过滤口径一致。 */
    @Test
    void shouldCountFilteredMatchPaginated() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        dlqRepository.recordFailure(new WalletAddressProvisionDlqEntry(
                null, "user-registered-cf-1", USER_ID_A, UID_A, "alice@example.com",
                1, WalletAddressProvisionDlqStatus.PENDING,
                "20007", "x", now, null, now));
        dlqRepository.recordFailure(new WalletAddressProvisionDlqEntry(
                null, "user-registered-cf-2", USER_ID_B, UID_B, "bob@example.com",
                1, WalletAddressProvisionDlqStatus.PENDING,
                "20007", "x", now, null, now));

        Assertions.assertEquals(2, dlqRepository.countFiltered(null, null));
        Assertions.assertEquals(1, dlqRepository.countFiltered(null, USER_ID_A));
        Assertions.assertEquals(1, dlqRepository.countFiltered(null, USER_ID_B));
        Assertions.assertEquals(2, dlqRepository.countFiltered(0, null));
        Assertions.assertEquals(0, dlqRepository.countFiltered(1, null));
    }

    /** TC-WP-023 markResolved 改 status=1 + resolved_at；二次 markResolved 不再生效。 */
    @Test
    void shouldMarkResolvedAndBeIdempotent() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        dlqRepository.recordFailure(new WalletAddressProvisionDlqEntry(
                null, "user-registered-mr", USER_ID_A, UID_A, "alice@example.com",
                1, WalletAddressProvisionDlqStatus.PENDING,
                "20007", "x", now, null, now));
        long id = dlqRepository.findByEventId("user-registered-mr").orElseThrow().id();

        Assertions.assertTrue(dlqRepository.markResolved(id), "首次 markResolved 必须返回 true");

        WalletAddressProvisionDlqEntry e = dlqRepository.findById(id).orElseThrow();
        Assertions.assertEquals(WalletAddressProvisionDlqStatus.RESOLVED, e.status());
        Assertions.assertNotNull(e.resolvedAt(), "resolvedAt 必须填充");

        // 二次调用应返回 false（status=0 已经不存在了，CAS 不命中）
        Assertions.assertFalse(dlqRepository.markResolved(id),
                "已 RESOLVED 后二次 markResolved 必须返回 false（CAS WHERE status=0 不命中）");
    }
}
