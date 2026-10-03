package com.falconx.wallet;

import static com.falconx.wallet.support.WalletExternalTestSupport.combinedOutput;
import static com.falconx.wallet.support.WalletExternalTestSupport.findRecentNativeTransfer;
import static com.falconx.wallet.support.WalletExternalTestSupport.waitForCondition;
import static com.falconx.wallet.support.WalletExternalTestSupport.waitForLog;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.falconx.domain.enums.ChainType;
import com.falconx.wallet.application.WalletDepositTrackingApplicationService;
import com.falconx.wallet.client.WalletBlockchainClientFactory;
import com.falconx.wallet.config.WalletServiceProperties;
import com.falconx.wallet.entity.WalletAddressAssignment;
import com.falconx.wallet.entity.WalletAddressStatus;
import com.falconx.wallet.entity.WalletChainCursor;
import com.falconx.wallet.entity.WalletDepositStatus;
import com.falconx.wallet.entity.WalletDepositTransaction;
import com.falconx.wallet.listener.ChainDepositListener;
import com.falconx.wallet.listener.Web3jChainDepositListener;
import com.falconx.wallet.repository.WalletAddressRepository;
import com.falconx.wallet.repository.WalletChainCursorRepository;
import com.falconx.wallet.repository.WalletDepositTransactionRepository;
import com.falconx.wallet.repository.mapper.test.WalletTestSupportMapper;
import com.falconx.wallet.support.WalletExternalTestSupport;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.web3j.protocol.Web3j;

/**
 * wallet 外部链节点真扫块自动化验证。
 *
 * <p>该类显式连到真实 ETH 节点，不使用 stub 冒充真源，用于补齐 Stage 6A 剩余的外部扫块自动化缺口。
 */
@EnabledIfEnvironmentVariable(named = "FALCONX_WALLET_EXTERNAL_TEST_ENABLED", matches = "(?i)true")
@EnabledIfEnvironmentVariable(named = "FALCONX_WALLET_ETH_RPC_URL", matches = ".+")
@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("stage5")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = {
                WalletServiceApplication.class,
                WalletExternalChainNodeAutomationIntegrationTests.TestWalletListenerConfiguration.class
        },
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_wallet_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.kafka.bootstrap-servers=localhost:9092"
        }
)
class WalletExternalChainNodeAutomationIntegrationTests {

    private static final Duration EXTERNAL_SCAN_INTERVAL = Duration.ofSeconds(2);

    @Autowired
    private WalletAddressRepository walletAddressRepository;

    @Autowired
    private WalletChainCursorRepository walletChainCursorRepository;

    @Autowired
    private WalletDepositTransactionRepository walletDepositTransactionRepository;

    @Autowired
    private WalletDepositTrackingApplicationService walletDepositTrackingApplicationService;

    @Autowired
    private WalletTestSupportMapper walletTestSupportMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WalletServiceProperties walletServiceProperties;

    @BeforeEach
    void cleanWalletTables() {
        walletTestSupportMapper.clearOwnerTables();
    }

    @Test
    void shouldTrackRealEthDepositAcrossCursorRescanAndPersistWalletTxId(CapturedOutput output) throws Exception {
        URI rpcUrl = WalletExternalTestSupport.externalEthRpcUrl();
        WalletBlockchainClientFactory walletBlockchainClientFactory = new WalletBlockchainClientFactory();
        Web3j probeClient = walletBlockchainClientFactory.createEvmClient(rpcUrl);
        Web3jChainDepositListener listener = null;
        try {
            WalletExternalTestSupport.NativeTransferCandidate candidate = findRecentNativeTransfer(probeClient, 25);
            int requiredConfirmations = candidate.confirmations() + 3;
            walletServiceProperties.getChains().getEth().setRequiredConfirmations(requiredConfirmations);
            long initialCursorValue = Math.max(0L, candidate.blockNumber() - 1L);
            long userId = 98001L;

            walletAddressRepository.save(new WalletAddressAssignment(
                    null,
                    userId,
                    ChainType.ETH,
                    "ETH",
                    "ERC20",
                    candidate.toAddress(),
                    1,
                    "manual:ETH:1",
                    WalletAddressStatus.ASSIGNED,
                    OffsetDateTime.now()
            ));
            walletChainCursorRepository.initializeIfAbsent(ChainType.ETH, "block", String.valueOf(initialCursorValue));

            listener = createEthListener(rpcUrl, requiredConfirmations, walletBlockchainClientFactory);
            listener.start(walletDepositTrackingApplicationService::trackObservedDeposit);

            waitForCondition(
                    Duration.ofSeconds(30),
                    "未在超时内把真实 ETH 原生币转账推进到 wallet owner，日志如下：" + System.lineSeparator() + combinedOutput(output),
                    () -> walletDepositTransactionRepository.findByChainAndTxHashAndLogIndex(ChainType.ETH, candidate.txHash(), 0)
                            .map(transaction -> transaction.status() == WalletDepositStatus.CONFIRMING)
                            .orElse(false)
            );

            WalletDepositTransaction tracked = walletDepositTransactionRepository
                    .findByChainAndTxHashAndLogIndex(ChainType.ETH, candidate.txHash(), 0)
                    .orElseThrow();
            Assertions.assertEquals(ChainType.ETH, tracked.chain());
            Assertions.assertEquals("ETH", tracked.token());
            Assertions.assertNull(tracked.tokenContractAddress());
            Assertions.assertEquals(0, tracked.logIndex());
            Assertions.assertEquals(userId, tracked.userId());
            Assertions.assertEquals(candidate.txHash(), tracked.txHash());
            Assertions.assertEquals(candidate.fromAddress(), tracked.fromAddress());
            Assertions.assertEquals(candidate.toAddress(), tracked.toAddress());
            Assertions.assertEquals(candidate.amount(), tracked.amount());
            Assertions.assertEquals(WalletDepositStatus.CONFIRMING, tracked.status());
            Assertions.assertNull(tracked.confirmedAt());
            Assertions.assertTrue(tracked.confirmations() < requiredConfirmations);

            Assertions.assertEquals(
                    1,
                    walletTestSupportMapper.countOutboxByEventTypeAndWalletTxId("wallet.deposit.detected", tracked.id())
            );
            JsonNode detectedPayload = objectMapper.readTree(
                    walletTestSupportMapper.selectLatestOutboxPayloadByEventTypeAndWalletTxId(
                            "wallet.deposit.detected",
                            tracked.id()
                    )
            );
            Assertions.assertEquals(tracked.id(), detectedPayload.path("walletTxId").longValue());
            Assertions.assertEquals(candidate.txHash(), detectedPayload.path("txHash").asText());
            Assertions.assertEquals("ETH", detectedPayload.path("token").asText());

            Assertions.assertNull(walletChainCursorRepository.findByChain(ChainType.BSC));
            Assertions.assertNull(walletChainCursorRepository.findByChain(ChainType.TRON));
            Assertions.assertNull(walletChainCursorRepository.findByChain(ChainType.SOL));

            WalletChainCursor cursor = walletChainCursorRepository.findByChain(ChainType.ETH);
            Assertions.assertNotNull(cursor);
            Assertions.assertTrue(Long.parseLong(cursor.cursorValue()) > initialCursorValue);

            Assertions.assertEquals(
                    1,
                    walletTestSupportMapper.countOutboxByEventTypeAndWalletTxId("wallet.deposit.detected", tracked.id())
            );

            waitForCondition(
                    Duration.ofSeconds(180),
                    "未在超时内把真实 ETH 原生币转账推进到 CONFIRMED，日志如下："
                            + System.lineSeparator() + combinedOutput(output),
                    () -> walletDepositTransactionRepository.findByChainAndTxHashAndLogIndex(ChainType.ETH, candidate.txHash(), 0)
                            .map(transaction -> transaction.status() == WalletDepositStatus.CONFIRMED)
                            .orElse(false)
            );

            WalletDepositTransaction confirmed = walletDepositTransactionRepository
                    .findByChainAndTxHashAndLogIndex(ChainType.ETH, candidate.txHash(), 0)
                    .orElseThrow();
            Assertions.assertEquals(tracked.id(), confirmed.id());
            Assertions.assertEquals(WalletDepositStatus.CONFIRMED, confirmed.status());
            Assertions.assertNotNull(confirmed.confirmedAt());
            Assertions.assertTrue(confirmed.confirmations() >= requiredConfirmations);
            Assertions.assertEquals(
                    1,
                    walletTestSupportMapper.countOutboxByEventTypeAndWalletTxId("wallet.deposit.detected", confirmed.id())
            );
            Assertions.assertEquals(
                    1,
                    walletTestSupportMapper.countOutboxByEventTypeAndWalletTxId("wallet.deposit.confirmed", confirmed.id())
            );
            JsonNode confirmedPayload = objectMapper.readTree(
                    walletTestSupportMapper.selectLatestOutboxPayloadByEventTypeAndWalletTxId(
                            "wallet.deposit.confirmed",
                            confirmed.id()
                    )
            );
            Assertions.assertEquals(confirmed.id(), confirmedPayload.path("walletTxId").longValue());
            Assertions.assertEquals(candidate.txHash(), confirmedPayload.path("txHash").asText());
            Assertions.assertEquals("ETH", confirmedPayload.path("token").asText());

            waitForLog(output, "wallet.listener.deposit.detected chain=ETH txHash=" + candidate.txHash(), Duration.ofSeconds(10));
            waitForLog(output, "wallet.listener.chainHead.synced chain=ETH", Duration.ofSeconds(10));
        } finally {
            if (listener != null) {
                listener.destroy();
            }
            probeClient.shutdown();
        }
    }

    @Test
    void shouldEmitReversalWhenConfirmedTransactionDisappearsFromRealRescanWindow(CapturedOutput output) throws Exception {
        URI rpcUrl = WalletExternalTestSupport.externalEthRpcUrl();
        WalletBlockchainClientFactory walletBlockchainClientFactory = new WalletBlockchainClientFactory();
        Web3j probeClient = walletBlockchainClientFactory.createEvmClient(rpcUrl);
        Web3jChainDepositListener listener = null;
        try {
            WalletExternalTestSupport.NativeTransferCandidate candidate = findRecentNativeTransfer(probeClient, 25);
            int requiredConfirmations = candidate.confirmations() + 3;
            walletServiceProperties.getChains().getEth().setRequiredConfirmations(requiredConfirmations);
            long userId = 98002L;
            String assignedAddress = "0x00000000000000000000000000000000a11ce001";
            String fakeTxHash = "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

            walletAddressRepository.save(new WalletAddressAssignment(
                    null,
                    userId,
                    ChainType.ETH,
                    "ETH",
                    "ERC20",
                    assignedAddress,
                    1,
                    "manual:ETH:1",
                    WalletAddressStatus.ASSIGNED,
                    OffsetDateTime.now()
            ));
            WalletDepositTransaction confirmed = walletDepositTransactionRepository.save(new WalletDepositTransaction(
                    null,
                    userId,
                    ChainType.ETH,
                    "ETH",
                    null,
                    fakeTxHash,
                    0,
                    "0x00000000000000000000000000000000b0b00001",
                    assignedAddress,
                    candidate.amount(),
                    candidate.blockNumber(),
                    requiredConfirmations,
                    requiredConfirmations,
                    WalletDepositStatus.CONFIRMED,
                    OffsetDateTime.now().minusMinutes(5),
                    OffsetDateTime.now().minusMinutes(4),
                    OffsetDateTime.now().minusMinutes(1)
            ));
            walletChainCursorRepository.initializeIfAbsent(
                    ChainType.ETH,
                    "block",
                    String.valueOf(candidate.latestBlockNumber())
            );

            listener = createEthListener(rpcUrl, requiredConfirmations, walletBlockchainClientFactory);
            listener.start(walletDepositTrackingApplicationService::trackObservedDeposit);

            waitForCondition(
                    Duration.ofSeconds(30),
                    "未在超时内观察到真实 ETH 回扫窗口里的 reversal，日志如下："
                            + System.lineSeparator() + combinedOutput(output),
                    () -> walletDepositTransactionRepository.findByChainAndTxHashAndLogIndex(ChainType.ETH, fakeTxHash, 0)
                            .map(transaction -> transaction.status() == WalletDepositStatus.REVERSED)
                            .orElse(false)
            );

            WalletDepositTransaction reversed = walletDepositTransactionRepository
                    .findByChainAndTxHashAndLogIndex(ChainType.ETH, fakeTxHash, 0)
                    .orElseThrow();
            Assertions.assertEquals(confirmed.id(), reversed.id());
            Assertions.assertEquals(WalletDepositStatus.REVERSED, reversed.status());
            Assertions.assertEquals(
                    1,
                    walletTestSupportMapper.countOutboxByEventTypeAndWalletTxId("wallet.deposit.reversed", reversed.id())
            );

            JsonNode reversedPayload = objectMapper.readTree(
                    walletTestSupportMapper.selectLatestOutboxPayloadByEventTypeAndWalletTxId(
                            "wallet.deposit.reversed",
                            reversed.id()
                    )
            );
            Assertions.assertEquals(reversed.id(), reversedPayload.path("walletTxId").longValue());
            Assertions.assertEquals(fakeTxHash, reversedPayload.path("txHash").asText());
            Assertions.assertEquals("ETH", reversedPayload.path("token").asText());

            waitForLog(output, "wallet.listener.deposit.reversedDetected chain=ETH txHash=" + fakeTxHash, Duration.ofSeconds(10));
        } finally {
            if (listener != null) {
                listener.destroy();
            }
            probeClient.shutdown();
        }
    }

    private Web3jChainDepositListener createEthListener(URI rpcUrl,
                                                        int requiredConfirmations,
                                                        WalletBlockchainClientFactory walletBlockchainClientFactory) {
        WalletServiceProperties.Chain chainProperties = new WalletServiceProperties.Chain();
        chainProperties.setRpcUrl(rpcUrl);
        chainProperties.setScanInterval(EXTERNAL_SCAN_INTERVAL);
        chainProperties.setRequiredConfirmations(requiredConfirmations);
        chainProperties.setCursorType("block");
        chainProperties.setInitialCursor("0");
        return new Web3jChainDepositListener(
                ChainType.ETH,
                chainProperties,
                walletBlockchainClientFactory,
                walletAddressRepository,
                walletChainCursorRepository,
                walletDepositTransactionRepository
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestWalletListenerConfiguration {

        @Bean("chainDepositListeners")
        List<ChainDepositListener> chainDepositListeners() {
            return List.of();
        }
    }
}
