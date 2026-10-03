package com.falconx.wallet;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.wallet.entity.WalletWithdrawTx;
import com.falconx.wallet.entity.WalletWithdrawTxStatus;
import com.falconx.wallet.repository.WalletWithdrawTxRepository;
import com.falconx.wallet.repository.mapper.test.WalletTestSupportMapper;
import com.falconx.wallet.withdraw.WalletWithdrawConfirmationScheduler;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

/**
 * STAGE-7-WITHDRAW Phase 3 R6：{@link WalletWithdrawConfirmationScheduler} 业务 IT。
 *
 * <p>覆盖：
 * <ul>
 *   <li>TC-WD-140 confirmations &lt; min → updateConfirmations(count) 写入；status 不变（BROADCAST）</li>
 *   <li>TC-WD-141 confirmations ≥ min → markConfirmedAtomic + outbox(wallet.withdraw.confirmed)</li>
 *   <li>TC-WD-142 receipt.status=0 → markFailedAtomic(20011) + outbox(wallet.withdraw.failed) 20011</li>
 *   <li>TC-WD-143 receipt 暂不可用（Optional.empty）→ 不变；下一 tick 重试</li>
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
                // 长间隔保证调度器自动 tick 不与本测试 tick 冲突（86_400_000ms = 24h）
                "falconx.wallet.withdraw.eth.confirmation-scheduler-interval=86400000ms",
                "falconx.wallet.withdraw.eth.min-confirmations=12"
        }
)
class WalletWithdrawConfirmationSchedulerIntegrationTests {

    private static final long USER_ID = 80060101L;
    private static final long WITHDRAW_ORDER_ID = 80060201L;
    private static final String FROM_ADDRESS = "0x6d40fa05f5f4d9a06a1a89ddfd09cd35dca6e45d";
    private static final String TARGET_ADDRESS = "0xEeEeEeEeEeEeEeEeEeEeEeEeEeEeEeEeEeEeEeEe";
    private static final String TX_HASH = "0xfeedbeef00112233445566778899aabbccddeeff00112233445566778899aabb";

    @Autowired
    private WalletWithdrawConfirmationScheduler scheduler;

    @Autowired
    private WalletWithdrawTxRepository repository;

    @Autowired
    private WalletTestSupportMapper supportMapper;

    @Autowired
    private IdGenerator idGenerator;

    @MockitoBean(name = "walletWithdrawWeb3j")
    private Web3j web3j;

    @BeforeEach
    void clean() {
        supportMapper.clearOwnerTables();
        Mockito.reset(web3j);
    }

    /** TC-WD-140：confirmations=5 (&lt; 12) → updateConfirmations(5) 写入；status 仍 BROADCAST(1)。 */
    @Test
    void shouldUpdateConfirmationsWhenBelowMinimum() throws Exception {
        long txId = insertBroadcastTx(100L);
        long broadcastBlock = 1000L;
        long currentBlock = 1004L; // confirmations = current - broadcast + 1 = 5
        stubEthBlockNumber(currentBlock);
        stubEthGetTransactionReceiptOk(broadcastBlock, BigInteger.valueOf(21000));

        scheduler.tick();

        Assertions.assertEquals(WalletWithdrawTxStatus.BROADCAST.code(),
                supportMapper.selectWithdrawTxStatusByWithdrawOrderId(WITHDRAW_ORDER_ID).intValue());
        WalletWithdrawTx after = repository.findById(txId).orElseThrow();
        Assertions.assertEquals(5, after.confirmations(),
                "confirmations 应记录为 5（current=1004, broadcast=1000）");
        Assertions.assertEquals(0, (int) supportMapper.countOutboxByEventType("wallet.withdraw.confirmed"));
        Assertions.assertEquals(0, (int) supportMapper.countOutboxByEventType("wallet.withdraw.failed"));
    }

    /** TC-WD-141：confirmations=12 ≥ 12 → status=CONFIRMED + outbox(wallet.withdraw.confirmed)。 */
    @Test
    void shouldMarkConfirmedAndPublishWhenAboveMinimum() throws Exception {
        long txId = insertBroadcastTx(101L);
        long broadcastBlock = 2000L;
        long currentBlock = 2011L; // confirmations = 12
        stubEthBlockNumber(currentBlock);
        stubEthGetTransactionReceiptOk(broadcastBlock, BigInteger.valueOf(21000));

        scheduler.tick();

        Assertions.assertEquals(WalletWithdrawTxStatus.CONFIRMED.code(),
                supportMapper.selectWithdrawTxStatusByWithdrawOrderId(WITHDRAW_ORDER_ID).intValue());
        Assertions.assertEquals(1, (int) supportMapper.countOutboxByEventType("wallet.withdraw.confirmed"));
        String payload = supportMapper.selectLatestOutboxPayloadByEventType("wallet.withdraw.confirmed");
        Assertions.assertTrue(payload.contains(String.valueOf(WITHDRAW_ORDER_ID)));
        Assertions.assertTrue(payload.contains(String.valueOf(broadcastBlock)),
                "confirmed payload 必须含 blockNumber=" + broadcastBlock + "：实际=" + payload);
    }

    /** TC-WD-142：receipt.status=0 (revert) → markFailedAtomic(20011) + outbox(wallet.withdraw.failed) 20011。 */
    @Test
    void shouldMarkFailedWhenReceiptStatusReverted() throws Exception {
        insertBroadcastTx(102L);
        stubEthBlockNumber(3010L);
        stubEthGetTransactionReceiptReverted(3000L);

        scheduler.tick();

        Assertions.assertEquals(WalletWithdrawTxStatus.FAILED.code(),
                supportMapper.selectWithdrawTxStatusByWithdrawOrderId(WITHDRAW_ORDER_ID).intValue());
        Assertions.assertEquals(1, (int) supportMapper.countOutboxByEventType("wallet.withdraw.failed"));
        Assertions.assertEquals(0, (int) supportMapper.countOutboxByEventType("wallet.withdraw.confirmed"));
        String payload = supportMapper.selectLatestOutboxPayloadByEventType("wallet.withdraw.failed");
        Assertions.assertTrue(payload.contains("20011"),
                "revert failed payload 必须含 failureCode 20011：实际=" + payload);
    }

    /** TC-WD-143：receipt 暂不可用（Optional.empty）→ status 不变；无新 outbox；下一 tick 可再次重试。 */
    @Test
    void shouldRetryNextTickWhenReceiptUnavailable() throws Exception {
        insertBroadcastTx(103L);
        stubEthBlockNumber(4010L);
        stubEthGetTransactionReceiptEmpty();

        scheduler.tick();

        Assertions.assertEquals(WalletWithdrawTxStatus.BROADCAST.code(),
                supportMapper.selectWithdrawTxStatusByWithdrawOrderId(WITHDRAW_ORDER_ID).intValue());
        Assertions.assertEquals(0, (int) supportMapper.countOutboxByEventType("wallet.withdraw.confirmed"));
        Assertions.assertEquals(0, (int) supportMapper.countOutboxByEventType("wallet.withdraw.failed"));

        // 下一 tick：receipt 转为 ok 12 confirmations → 应顺利推进到 CONFIRMED
        Mockito.reset(web3j);
        stubEthBlockNumber(4011L);
        stubEthGetTransactionReceiptOk(4000L, BigInteger.valueOf(21000));
        scheduler.tick();

        Assertions.assertEquals(WalletWithdrawTxStatus.CONFIRMED.code(),
                supportMapper.selectWithdrawTxStatusByWithdrawOrderId(WITHDRAW_ORDER_ID).intValue());
        Assertions.assertEquals(1, (int) supportMapper.countOutboxByEventType("wallet.withdraw.confirmed"));
    }

    private long insertBroadcastTx(long nonce) {
        long id = idGenerator.nextId();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        WalletWithdrawTx signing = new WalletWithdrawTx(
                id, WITHDRAW_ORDER_ID, USER_ID, "ERC20",
                FROM_ADDRESS, TARGET_ADDRESS, new BigDecimal("100.000000"),
                nonce, new BigDecimal("5000000000"), null, null,
                null, null, 0,
                WalletWithdrawTxStatus.SIGNING, null, null,
                null, null, null, now, now
        );
        repository.insert(signing);
        repository.markBroadcastAtomic(id, TX_HASH, new BigDecimal("5000000000"), now);
        return id;
    }

    private void stubEthBlockNumber(long blockNumber) throws IOException {
        EthBlockNumber resp = new EthBlockNumber();
        resp.setResult("0x" + Long.toHexString(blockNumber));
        @SuppressWarnings("unchecked")
        Request<?, EthBlockNumber> req = Mockito.mock(Request.class);
        Mockito.when(req.send()).thenReturn(resp);
        Mockito.doReturn(req).when(web3j).ethBlockNumber();
    }

    private void stubEthGetTransactionReceiptOk(long blockNumber, BigInteger gasUsed) throws IOException {
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setTransactionHash(TX_HASH);
        receipt.setBlockNumber("0x" + Long.toHexString(blockNumber));
        receipt.setStatus("0x1");
        receipt.setGasUsed("0x" + gasUsed.toString(16));
        EthGetTransactionReceipt resp = new EthGetTransactionReceipt();
        resp.setResult(receipt);
        @SuppressWarnings("unchecked")
        Request<?, EthGetTransactionReceipt> req = Mockito.mock(Request.class);
        Mockito.when(req.send()).thenReturn(resp);
        Mockito.doReturn(req).when(web3j).ethGetTransactionReceipt(Mockito.eq(TX_HASH));
    }

    private void stubEthGetTransactionReceiptReverted(long blockNumber) throws IOException {
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setTransactionHash(TX_HASH);
        receipt.setBlockNumber("0x" + Long.toHexString(blockNumber));
        receipt.setStatus("0x0");
        receipt.setGasUsed("0x5208");
        EthGetTransactionReceipt resp = new EthGetTransactionReceipt();
        resp.setResult(receipt);
        @SuppressWarnings("unchecked")
        Request<?, EthGetTransactionReceipt> req = Mockito.mock(Request.class);
        Mockito.when(req.send()).thenReturn(resp);
        Mockito.doReturn(req).when(web3j).ethGetTransactionReceipt(Mockito.eq(TX_HASH));
    }

    private void stubEthGetTransactionReceiptEmpty() throws IOException {
        EthGetTransactionReceipt resp = new EthGetTransactionReceipt() {
            @Override
            public Optional<TransactionReceipt> getTransactionReceipt() {
                return Optional.empty();
            }
        };
        @SuppressWarnings("unchecked")
        Request<?, EthGetTransactionReceipt> req = Mockito.mock(Request.class);
        Mockito.when(req.send()).thenReturn(resp);
        Mockito.doReturn(req).when(web3j).ethGetTransactionReceipt(Mockito.eq(TX_HASH));
    }
}
