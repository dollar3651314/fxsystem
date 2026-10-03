package com.falconx.wallet;

import com.falconx.trading.contract.event.TradingWithdrawReviewedEventPayload;
import com.falconx.wallet.entity.WalletWithdrawTxStatus;
import com.falconx.wallet.kms.KmsSigner;
import com.falconx.wallet.kms.KmsSignerStub;
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
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;

/**
 * STAGE-7-WITHDRAW Phase 3 R6：TC-WD-121 KmsSignerStub 兜底场景 IT。
 *
 * <p>不配置 {@code falconx.wallet.kms.erc20.private-key-pem} → {@code @ConditionalOnExpression}
 * 不激活 {@code LocalKmsSigner} → {@code KmsSignerStub} 通过 {@code @ConditionalOnMissingBean} 注入。
 *
 * <p>断言：调用 broadcast 时 sign() 抛 UnsupportedOperationException → markFailedAtomic 20014 +
 * outbox(wallet.withdraw.failed) failureCode=20014。
 */
@ActiveProfiles("stage5")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = WalletServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_wallet_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                // 关键：不设 erc20.private-key-pem → @ConditionalOnExpression 不激活 LocalKmsSigner
                "falconx.wallet.kms.erc20.private-key-pem=",
                "falconx.wallet.kms.erc20.from-address=0x6D40fa05f5f4D9A06A1a89dDFd09cd35Dca6E45D",
                "falconx.wallet.kms.trc20.private-key-pem=",
                "falconx.wallet.kms.trc20.from-address=",
                "falconx.wallet.withdraw.eth.usdt-contract=0xF31429D9133f91221aa4Dca7Cdfc626512BAdeBb",
                "falconx.wallet.withdraw.eth.usdt-decimals=6",
                "falconx.wallet.withdraw.eth.usdc-contract=0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238",
                "falconx.wallet.withdraw.eth.usdc-decimals=6",
                "falconx.wallet.withdraw.eth.chain-id=11155111",
                "falconx.wallet.withdraw.eth.confirmation-scheduler-interval=86400000ms"
        }
)
class WalletWithdrawBroadcastWithStubSignerIntegrationTests {

    private static final long USER_ID = 80050101L;
    private static final long WITHDRAW_ORDER_ID = 80050201L;
    private static final String TARGET_ADDRESS = "0xDdDdDdDdDdDdDdDdDdDdDdDdDdDdDdDdDdDdDdDd";

    @Autowired
    private WalletWithdrawBroadcastApplicationService broadcastService;

    @Autowired
    private WalletTestSupportMapper supportMapper;

    @Autowired
    private KmsSigner kmsSigner;

    @MockitoBean(name = "walletWithdrawWeb3j")
    private Web3j web3j;

    @BeforeEach
    void clean() throws Exception {
        supportMapper.clearOwnerTables();
        Mockito.reset(web3j);
        EthGasPrice gas = new EthGasPrice();
        gas.setResult("0x" + new BigInteger("5000000000").toString(16));
        @SuppressWarnings("unchecked")
        Request<?, EthGasPrice> gasReq = Mockito.mock(Request.class);
        Mockito.when(gasReq.send()).thenReturn(gas);
        Mockito.doReturn(gasReq).when(web3j).ethGasPrice();
        EthGetTransactionCount nonce = new EthGetTransactionCount();
        nonce.setResult("0x0");
        @SuppressWarnings("unchecked")
        Request<?, EthGetTransactionCount> nonceReq = Mockito.mock(Request.class);
        Mockito.when(nonceReq.send()).thenReturn(nonce);
        Mockito.doReturn(nonceReq).when(web3j).ethGetTransactionCount(Mockito.anyString(), Mockito.any());
    }

    /** TC-WD-121：Stub 兜底 → 签名时 UnsupportedOperationException → tx FAILED 20014 + outbox failed 20014。 */
    @Test
    void shouldMarkFailedWithSignerUnavailableWhenStubBootstrapped() throws IOException {
        Assertions.assertInstanceOf(KmsSignerStub.class, kmsSigner,
                "未配置私钥时必须注入 KmsSignerStub 兜底");

        TradingWithdrawReviewedEventPayload reviewed = new TradingWithdrawReviewedEventPayload(
                WITHDRAW_ORDER_ID, USER_ID, "APPROVED", 999L,
                OffsetDateTime.now(ZoneOffset.UTC), null, null,
                new BigDecimal("100.000000"), "USDT", "ERC20", TARGET_ADDRESS);
        broadcastService.broadcast(reviewed);

        Assertions.assertEquals(1, (int) supportMapper.countWithdrawTxByWithdrawOrderId(WITHDRAW_ORDER_ID));
        Assertions.assertEquals(WalletWithdrawTxStatus.FAILED.code(),
                supportMapper.selectWithdrawTxStatusByWithdrawOrderId(WITHDRAW_ORDER_ID).intValue());
        Assertions.assertEquals(1, (int) supportMapper.countOutboxByEventType("wallet.withdraw.failed"));
        Assertions.assertEquals(0, (int) supportMapper.countOutboxByEventType("wallet.withdraw.broadcast"));
        String payload = supportMapper.selectLatestOutboxPayloadByEventType("wallet.withdraw.failed");
        Assertions.assertTrue(payload.contains("20014"),
                "Stub 路径 failed payload 必须含 failureCode 20014（SIGNER_UNAVAILABLE）：实际=" + payload);

        // sendRawTransaction 永不应被调用（签名前已失败）
        Mockito.verify(web3j, Mockito.never()).ethSendRawTransaction(Mockito.anyString());
    }
}
