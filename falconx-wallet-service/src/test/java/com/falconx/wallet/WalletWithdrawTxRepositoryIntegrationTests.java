package com.falconx.wallet;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.wallet.entity.WalletWithdrawTx;
import com.falconx.wallet.entity.WalletWithdrawTxStatus;
import com.falconx.wallet.kms.KmsSigner;
import com.falconx.wallet.kms.KmsSignerStub;
import com.falconx.wallet.repository.WalletWithdrawTxRepository;
import com.falconx.wallet.repository.mapper.test.WalletTestSupportMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * STAGE-7-WITHDRAW Phase 3 Commit 8 R6：t_withdraw_tx 仓储 IT + KmsSigner stub 注入验证。
 *
 * <p>覆盖：
 * <ul>
 *   <li>insert / findById / findByWithdrawOrderId</li>
 *   <li>SIGNING → BROADCAST → CONFIRMED 三阶段 CAS</li>
 *   <li>uk_withdraw_order 唯一约束</li>
 *   <li>uk_network_nonce 唯一约束（同链同 from 同 nonce 重复 insert 抛）</li>
 *   <li>未配置 LocalKmsSigner 时 Spring 注入 {@link KmsSignerStub} 兜底</li>
 * </ul>
 */
@ActiveProfiles("stage5")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = WalletServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_wallet_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "falconx.wallet.kms.erc20.private-key-pem=",
                "falconx.wallet.kms.erc20.from-address=",
                "falconx.wallet.kms.trc20.private-key-pem=",
                "falconx.wallet.kms.trc20.from-address="
        }
)
class WalletWithdrawTxRepositoryIntegrationTests {

    private static final long USER_ID = 80020101L;
    private static final long WITHDRAW_ORDER_ID = 80020201L;
    private static final String FROM_ADDRESS = "0xAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaa";
    private static final String TARGET_ADDRESS = "0xBbbbbbbbBbbbbbbbBbbbbbbbBbbbbbbbBbbbbbbb";

    @Autowired
    private WalletWithdrawTxRepository repository;

    @Autowired
    private WalletTestSupportMapper supportMapper;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private KmsSigner kmsSigner;

    @BeforeEach
    void clean() {
        supportMapper.clearOwnerTables();
    }

    /** 注入兜底：未配置 LocalKmsSigner 时 Spring 注入 {@link KmsSignerStub}。 */
    @Test
    void shouldInjectKmsSignerStubWhenLocalNotConfigured() {
        Assertions.assertNotNull(kmsSigner);
        Assertions.assertInstanceOf(KmsSignerStub.class, kmsSigner);
        Assertions.assertFalse(kmsSigner.supports("ERC20"));
    }

    /** 基础 insert + 查回（status=SIGNING + nonce + tx_hash=null）。 */
    @Test
    void shouldInsertWithdrawTxAndReadBack() {
        long id = idGenerator.nextId();
        WalletWithdrawTx tx = newSigningTx(id, WITHDRAW_ORDER_ID, 100L, null);

        repository.insert(tx);

        WalletWithdrawTx loaded = repository.findById(id).orElseThrow();
        Assertions.assertEquals(WalletWithdrawTxStatus.SIGNING, loaded.status());
        Assertions.assertEquals(WITHDRAW_ORDER_ID, loaded.withdrawOrderId());
        Assertions.assertEquals(100L, loaded.nonce());
        Assertions.assertNull(loaded.txHash());

        Assertions.assertTrue(repository.findByWithdrawOrderId(WITHDRAW_ORDER_ID).isPresent());
    }

    /** uk_withdraw_order：同 withdraw_order_id 重复 insert 抛 DuplicateKeyException。 */
    @Test
    void shouldRejectDuplicateWithdrawOrderId() {
        repository.insert(newSigningTx(idGenerator.nextId(), WITHDRAW_ORDER_ID, 100L, null));
        Assertions.assertThrows(DuplicateKeyException.class,
                () -> repository.insert(newSigningTx(idGenerator.nextId(), WITHDRAW_ORDER_ID, 101L, null)));
    }

    /** uk_network_nonce：同 network + from_address + nonce 重复 insert 抛。 */
    @Test
    void shouldRejectDuplicateNetworkNonce() {
        repository.insert(newSigningTx(idGenerator.nextId(), WITHDRAW_ORDER_ID, 100L, null));
        Assertions.assertThrows(DuplicateKeyException.class,
                () -> repository.insert(newSigningTx(idGenerator.nextId(), WITHDRAW_ORDER_ID + 1, 100L, null)));
    }

    /** SIGNING → BROADCAST CAS：成功更新 1 行 + tx_hash 落库。 */
    @Test
    void shouldMarkBroadcastAtomically() {
        long id = idGenerator.nextId();
        repository.insert(newSigningTx(id, WITHDRAW_ORDER_ID, 100L, null));

        OffsetDateTime broadcastAt = OffsetDateTime.now(ZoneOffset.UTC);
        int updated = repository.markBroadcastAtomic(id, "0xtxhash001",
                new BigDecimal("0.00000002"), broadcastAt);

        Assertions.assertEquals(1, updated);
        Assertions.assertEquals(1, supportMapper.selectWithdrawTxStatusByWithdrawOrderId(WITHDRAW_ORDER_ID));
        Assertions.assertEquals("0xtxhash001", supportMapper.selectWithdrawTxTxHashByWithdrawOrderId(WITHDRAW_ORDER_ID));

        // 再次 CAS：状态已是 BROADCAST，不能再切回 BROADCAST → 0 行
        int again = repository.markBroadcastAtomic(id, "0xtxhash002",
                new BigDecimal("0.00000003"), broadcastAt);
        Assertions.assertEquals(0, again);
    }

    /** BROADCAST → CONFIRMED CAS：写 block_number + confirmations + gas。 */
    @Test
    void shouldMarkConfirmedAtomically() {
        long id = idGenerator.nextId();
        repository.insert(newSigningTx(id, WITHDRAW_ORDER_ID, 100L, null));
        OffsetDateTime broadcastAt = OffsetDateTime.now(ZoneOffset.UTC);
        repository.markBroadcastAtomic(id, "0xtxhash003", new BigDecimal("0.00000002"), broadcastAt);

        int updated = repository.markConfirmedAtomic(id, 19000000L, 12,
                new BigDecimal("21000"), new BigDecimal("4.20"),
                OffsetDateTime.now(ZoneOffset.UTC));

        Assertions.assertEquals(1, updated);
        Assertions.assertEquals(2, supportMapper.selectWithdrawTxStatusByWithdrawOrderId(WITHDRAW_ORDER_ID));
    }

    /** 任意非终态 → FAILED CAS：SIGNING 直接 FAILED 也允许。 */
    @Test
    void shouldMarkFailedFromSigning() {
        long id = idGenerator.nextId();
        repository.insert(newSigningTx(id, WITHDRAW_ORDER_ID, 100L, null));

        int updated = repository.markFailedAtomic(id, "20014", "kms-unavailable",
                OffsetDateTime.now(ZoneOffset.UTC));

        Assertions.assertEquals(1, updated);
        Assertions.assertEquals(3, supportMapper.selectWithdrawTxStatusByWithdrawOrderId(WITHDRAW_ORDER_ID));

        // CONFIRMED → FAILED 应该被拒绝；先把 tx 切到 CONFIRMED 才能验证（此用例已 FAILED，跳过）
    }

    /** confirmations 更新（BROADCAST 期间增量）。 */
    @Test
    void shouldUpdateConfirmationsWhileBroadcast() {
        long id = idGenerator.nextId();
        repository.insert(newSigningTx(id, WITHDRAW_ORDER_ID, 100L, null));
        repository.markBroadcastAtomic(id, "0xtxhash004", new BigDecimal("0.00000002"),
                OffsetDateTime.now(ZoneOffset.UTC));

        Assertions.assertEquals(1, repository.updateConfirmations(id, 3));
        Assertions.assertEquals(1, repository.updateConfirmations(id, 6));
        // 切到 CONFIRMED 后 updateConfirmations 应不再更新（status != BROADCAST）
        repository.markConfirmedAtomic(id, 19000001L, 12,
                new BigDecimal("21000"), new BigDecimal("4.20"),
                OffsetDateTime.now(ZoneOffset.UTC));
        Assertions.assertEquals(0, repository.updateConfirmations(id, 20));
    }

    private WalletWithdrawTx newSigningTx(long id, long withdrawOrderId, long nonce, String txHash) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new WalletWithdrawTx(
                id,
                withdrawOrderId,
                USER_ID,
                "ERC20",
                FROM_ADDRESS,
                TARGET_ADDRESS,
                new BigDecimal("100.00000000"),
                nonce,
                null, null, null,
                txHash, null, 0,
                WalletWithdrawTxStatus.SIGNING,
                null, null,
                null, null, null,
                now, now
        );
    }
}
