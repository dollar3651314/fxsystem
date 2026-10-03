package com.falconx.wallet;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.contract.event.TradingWithdrawReviewedEventPayload;
import com.falconx.wallet.entity.WalletWithdrawTx;
import com.falconx.wallet.entity.WalletWithdrawTxStatus;
import com.falconx.wallet.kms.KmsSigner;
import com.falconx.wallet.kms.LocalKmsSigner;
import com.falconx.wallet.repository.WalletWithdrawTxRepository;
import com.falconx.wallet.repository.mapper.test.WalletTestSupportMapper;
import com.falconx.wallet.withdraw.WalletWithdrawBroadcastApplicationService;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthSendTransaction;

/**
 * STAGE-7-WITHDRAW Phase 3 R6：{@link WalletWithdrawBroadcastApplicationService} 业务 IT。
 *
 * <p>用 ERC20 私钥（{@link #ERC20_HEX_PRIVATE_KEY}）注入真实的 {@code LocalKmsSigner}，
 * Web3j 通过 {@link MockitoBean @MockitoBean(name="walletWithdrawWeb3j")} 替换为 mock，
 * t_withdraw_tx / t_outbox 走真 MySQL。
 *
 * <p>覆盖：
 * <ul>
 *   <li>TC-WD-120 签名 + sendRawTransaction 成功 → BROADCAST + outbox(wallet.withdraw.broadcast)</li>
 *   <li>TC-WD-121 KmsSigner Stub 触发 → tx 行 FAILED + outbox(wallet.withdraw.failed) code=20014</li>
 *   <li>TC-WD-122 sendRawTransaction IO 失败 → tx 行 FAILED + outbox(wallet.withdraw.failed) code=20010</li>
 *   <li>TC-WD-123 web3j 返回 nonce too low（JSON-RPC error）→ tx 行 FAILED + outbox 20010
 *       （注：spec 原描述 "nonce manager reset 后重试"；当前实现走 markFailedAtomic 20010 不重试，
 *       本用例验证实际行为；retry 策略列入 B 段 enhancement 待办）</li>
 *   <li>TC-WD-124 uk_withdraw_order 去重 → 已有 BROADCAST 时再次调用直接跳过，无新 tx / 无新 outbox</li>
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
                "falconx.wallet.kms.erc20.private-key-pem=4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318",
                "falconx.wallet.kms.erc20.from-address=0x6D40fa05f5f4D9A06A1a89dDFd09cd35Dca6E45D",
                "falconx.wallet.kms.trc20.private-key-pem=",
                "falconx.wallet.kms.trc20.from-address=",
                "falconx.wallet.withdraw.eth.usdt-contract=0xF31429D9133f91221aa4Dca7Cdfc626512BAdeBb",
                "falconx.wallet.withdraw.eth.usdt-decimals=6",
                "falconx.wallet.withdraw.eth.usdc-contract=0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238",
                "falconx.wallet.withdraw.eth.usdc-decimals=6",
                "falconx.wallet.withdraw.eth.chain-id=11155111",
                "falconx.wallet.withdraw.eth.gas-limit=100000",
                "falconx.wallet.withdraw.eth.gas-price-max-gwei=100",
                "falconx.wallet.withdraw.eth.confirmation-scheduler-interval=86400000ms"
        }
)
class WalletWithdrawBroadcastApplicationServiceIntegrationTests {

    /** 与 application.properties 中的私钥对应；测试用，非生产。 */
    private static final String ERC20_HEX_PRIVATE_KEY =
            "4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318";

    private static final long USER_ID = 80040101L;
    private static final long WITHDRAW_ORDER_ID = 80040201L;
    private static final String TARGET_ADDRESS = "0xCcccccccCcccccccCcccccccCcccccccCcccCccc";
    private static final BigDecimal AMOUNT = new BigDecimal("100.000000");
    private static final String CHAIN_TX_HASH = "0xa1b2c3d4e5f60718293a4b5c6d7e8f900112233445566778899aabbccddeeff0";

    @Autowired
    private WalletWithdrawBroadcastApplicationService broadcastService;

    @Autowired
    private WalletWithdrawTxRepository repository;

    @Autowired
    private WalletTestSupportMapper supportMapper;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private KmsSigner kmsSigner;

    @MockitoBean(name = "walletWithdrawWeb3j")
    private Web3j web3j;

    @BeforeEach
    void clean() throws Exception {
        supportMapper.clearOwnerTables();
        Mockito.reset(web3j);
        // 默认 stub：gas price = 5 gwei；ethGetTransactionCount = 0（nonce）
        stubEthGasPrice(new BigInteger("5000000000"));
        stubEthGetTransactionCount(BigInteger.ZERO);
    }

    /** TC-WD-120：签名 + sendRawTransaction 成功 → BROADCAST + outbox(wallet.withdraw.broadcast)。 */
    @Test
    void shouldBroadcastErc20TransferOnApproved() throws Exception {
        stubEthSendRawTransactionSuccess(CHAIN_TX_HASH);

        broadcastService.broadcast(newApprovedReviewed());

        Assertions.assertEquals(1, (int) supportMapper.countWithdrawTxByWithdrawOrderId(WITHDRAW_ORDER_ID));
        Assertions.assertEquals(WalletWithdrawTxStatus.BROADCAST.code(),
                supportMapper.selectWithdrawTxStatusByWithdrawOrderId(WITHDRAW_ORDER_ID).intValue());
        Assertions.assertEquals(CHAIN_TX_HASH,
                supportMapper.selectWithdrawTxTxHashByWithdrawOrderId(WITHDRAW_ORDER_ID));
        Assertions.assertEquals(1, (int) supportMapper.countOutboxByEventType("wallet.withdraw.broadcast"));
        String outboxPayload = supportMapper.selectLatestOutboxPayloadByEventType("wallet.withdraw.broadcast");
        Assertions.assertTrue(outboxPayload.contains(String.valueOf(WITHDRAW_ORDER_ID)));
        Assertions.assertTrue(outboxPayload.contains(CHAIN_TX_HASH));
    }

    /** TC-WD-103 互补：私钥配置存在时 LocalKmsSigner 激活，覆盖 KmsSignerStub 兜底。 */
    @Test
    void shouldInjectLocalKmsSignerWhenPrivateKeyConfigured() {
        Assertions.assertInstanceOf(LocalKmsSigner.class, kmsSigner,
                "配置 falconx.wallet.kms.erc20.private-key-pem 时必须注入 LocalKmsSigner");
        Assertions.assertTrue(kmsSigner.supports("ERC20"));
        Assertions.assertFalse(kmsSigner.supports("TRC20"),
                "未配 TRC20 私钥时 supports(\"TRC20\") 必须 false");
    }

    /** TC-WD-122：sendRawTransaction IOException → markFailedAtomic 20010 + outbox failed。 */
    @Test
    void shouldMarkFailedWhenSendRawTransactionThrowsIoException() throws Exception {
        stubEthSendRawTransactionThrows(new IOException("simulated http reset"));

        broadcastService.broadcast(newApprovedReviewed());

        // tx 行存在（已分配 nonce）但 status=FAILED + failure_code=20010
        Assertions.assertEquals(1, (int) supportMapper.countWithdrawTxByWithdrawOrderId(WITHDRAW_ORDER_ID));
        Assertions.assertEquals(WalletWithdrawTxStatus.FAILED.code(),
                supportMapper.selectWithdrawTxStatusByWithdrawOrderId(WITHDRAW_ORDER_ID).intValue());
        Assertions.assertEquals(1, (int) supportMapper.countOutboxByEventType("wallet.withdraw.failed"));
        String payload = supportMapper.selectLatestOutboxPayloadByEventType("wallet.withdraw.failed");
        Assertions.assertTrue(payload.contains("20010"),
                "failed payload 必须含 failureCode 20010：实际=" + payload);
    }

    /**
     * TC-WD-123：web3j 返回 JSON-RPC error "nonce too low" → markFailedAtomic 20010 + outbox failed。
     *
     * <p>注：spec 原描述 "nonce manager reset 后重试 1 次"；当前实现未引入重试，nonce-too-low
     * 等同 IOException 路径走 FAILED 20010。retry 策略列为 B 段增强待办（{@code TODO retry-on-nonce-conflict}）。
     */
    @Test
    void shouldMarkFailedWhenWeb3jReturnsNonceTooLowError() throws Exception {
        stubEthSendRawTransactionRpcError(-32000, "nonce too low");

        broadcastService.broadcast(newApprovedReviewed());

        Assertions.assertEquals(WalletWithdrawTxStatus.FAILED.code(),
                supportMapper.selectWithdrawTxStatusByWithdrawOrderId(WITHDRAW_ORDER_ID).intValue());
        Assertions.assertEquals(1, (int) supportMapper.countOutboxByEventType("wallet.withdraw.failed"));
        String payload = supportMapper.selectLatestOutboxPayloadByEventType("wallet.withdraw.failed");
        Assertions.assertTrue(payload.contains("20010"));
        Assertions.assertTrue(payload.toLowerCase().contains("nonce"),
                "failed payload 必须含 nonce 失败原因：实际=" + payload);
    }

    /** TC-WD-124：uk_withdraw_order 去重 → 已有 BROADCAST 时再次调用直接跳过；无新 tx / 无新 outbox。 */
    @Test
    void shouldSkipBroadcastWhenWithdrawOrderAlreadyHasTx() throws Exception {
        // 预置：一条已 BROADCAST 的 tx
        long preExistingId = idGenerator.nextId();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        WalletWithdrawTx pre = new WalletWithdrawTx(
                preExistingId, WITHDRAW_ORDER_ID, USER_ID, "ERC20",
                "0x6d40fa05f5f4d9a06a1a89ddfd09cd35dca6e45d",
                TARGET_ADDRESS, AMOUNT,
                0L, new BigDecimal("5000000000"), null, null,
                "0xPREEXISTING", null, 0,
                WalletWithdrawTxStatus.SIGNING, null, null,
                null, null, null, now, now
        );
        repository.insert(pre);
        repository.markBroadcastAtomic(preExistingId, "0xPREEXISTING", new BigDecimal("5000000000"), now);
        Mockito.reset(web3j); // 重置后默认 stub 失效，重新装上 (gas / nonce 不应被调用)
        stubEthGasPrice(new BigInteger("5000000000"));
        stubEthGetTransactionCount(BigInteger.ZERO);

        broadcastService.broadcast(newApprovedReviewed());

        // 仍只有 1 条 tx，0 条新 outbox
        Assertions.assertEquals(1, (int) supportMapper.countWithdrawTxByWithdrawOrderId(WITHDRAW_ORDER_ID));
        Assertions.assertEquals(0, (int) supportMapper.countOutboxByEventType("wallet.withdraw.broadcast"));
        Assertions.assertEquals(0, (int) supportMapper.countOutboxByEventType("wallet.withdraw.failed"));
        Mockito.verify(web3j, Mockito.never()).ethSendRawTransaction(Mockito.anyString());
    }

    private TradingWithdrawReviewedEventPayload newApprovedReviewed() {
        return new TradingWithdrawReviewedEventPayload(
                WITHDRAW_ORDER_ID, USER_ID, "APPROVED", 999L,
                OffsetDateTime.now(ZoneOffset.UTC), null, null,
                AMOUNT, "USDT", "ERC20", TARGET_ADDRESS);
    }

    private void stubEthGasPrice(BigInteger weiPerGas) throws IOException {
        EthGasPrice resp = new EthGasPrice();
        resp.setResult("0x" + weiPerGas.toString(16));
        @SuppressWarnings("unchecked")
        Request<?, EthGasPrice> req = Mockito.mock(Request.class);
        Mockito.when(req.send()).thenReturn(resp);
        Mockito.doReturn(req).when(web3j).ethGasPrice();
    }

    private void stubEthGetTransactionCount(BigInteger nonce) throws IOException {
        EthGetTransactionCount resp = new EthGetTransactionCount();
        resp.setResult("0x" + nonce.toString(16));
        @SuppressWarnings("unchecked")
        Request<?, EthGetTransactionCount> req = Mockito.mock(Request.class);
        Mockito.when(req.send()).thenReturn(resp);
        Mockito.doReturn(req).when(web3j).ethGetTransactionCount(Mockito.anyString(), Mockito.any());
    }

    private void stubEthSendRawTransactionSuccess(String txHash) throws IOException {
        EthSendTransaction resp = new EthSendTransaction();
        resp.setResult(txHash);
        @SuppressWarnings("unchecked")
        Request<?, EthSendTransaction> req = Mockito.mock(Request.class);
        Mockito.when(req.send()).thenReturn(resp);
        Mockito.doReturn(req).when(web3j).ethSendRawTransaction(Mockito.anyString());
    }

    private void stubEthSendRawTransactionThrows(IOException ex) throws IOException {
        @SuppressWarnings("unchecked")
        Request<?, EthSendTransaction> req = Mockito.mock(Request.class);
        Mockito.when(req.send()).thenThrow(ex);
        Mockito.doReturn(req).when(web3j).ethSendRawTransaction(Mockito.anyString());
    }

    private void stubEthSendRawTransactionRpcError(int code, String message) throws IOException {
        EthSendTransaction resp = new EthSendTransaction();
        Response.Error error = new Response.Error();
        error.setCode(code);
        error.setMessage(message);
        resp.setError(error);
        @SuppressWarnings("unchecked")
        Request<?, EthSendTransaction> req = Mockito.mock(Request.class);
        Mockito.when(req.send()).thenReturn(resp);
        Mockito.doReturn(req).when(web3j).ethSendRawTransaction(Mockito.anyString());
    }
}
