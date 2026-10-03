package com.falconx.wallet.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.falconx.domain.enums.ChainType;
import com.falconx.wallet.client.TronChainClient;
import com.falconx.wallet.client.WalletBlockchainClientFactory;
import com.falconx.wallet.config.WalletServiceProperties;
import com.falconx.wallet.entity.WalletChainCursor;
import com.falconx.wallet.repository.WalletAddressRepository;
import com.falconx.wallet.repository.WalletChainCursorRepository;
import com.falconx.wallet.repository.WalletDepositTransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.tron.trident.utils.Base58Check;
import org.tron.trident.utils.Numeric;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

/**
 * PROD-OPS-EVIDENCE-01 C4：链扫块 + 入金检测热路径 Micrometer 指标埋点测试。
 *
 * <p>用 {@link SimpleMeterRegistry} 验证：
 *
 * <ul>
 *   <li>EVM（Web3j）单链单轮扫块后 {@code falconx.wallet.chain.scan.duration{chain=eth}} 有 timer 记录，
 *       检测到原生币入金后 {@code falconx.wallet.deposit.detected.total{chain=eth}} count 增加</li>
 *   <li>TRON（java-tron HTTP）单链单轮扫块后 {@code falconx.wallet.chain.scan.duration{chain=tron}} 有 timer 记录，
 *       检测到 TRC20 入金后 {@code falconx.wallet.deposit.detected.total{chain=tron}} count 增加</li>
 * </ul>
 *
 * <p>tag 仅低基数固定链枚举（chain=eth|bsc|tron|sol，取 {@link ChainType} 枚举名小写），不含敏感值。
 */
class ChainDepositListenerMetricsTests {

    private static final String RAW_CONTRACT = "a614f803b6fd780986a42c78ec9c7f77e6ded13c";
    private static final String RAW_FROM = "79309abcff2cf531070ca9222a1f72c4a5136874";
    private static final String RAW_TO = "81b64b1c09d448d25c9eeb3ee3b8f3348a694c96";
    private static final String TRC20_TRANSFER_TOPIC =
            "ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    @Test
    void shouldRecordScanDurationAndDetectedCounterForEvmNativeDeposit() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WalletBlockchainClientFactory walletBlockchainClientFactory = Mockito.mock(WalletBlockchainClientFactory.class);
        WalletAddressRepository walletAddressRepository = Mockito.mock(WalletAddressRepository.class);
        WalletChainCursorRepository walletChainCursorRepository = Mockito.mock(WalletChainCursorRepository.class);
        WalletDepositTransactionRepository walletDepositTransactionRepository =
                Mockito.mock(WalletDepositTransactionRepository.class);
        Web3j web3j = Mockito.mock(Web3j.class);
        @SuppressWarnings("unchecked")
        Request<?, EthBlockNumber> blockNumberRequest = Mockito.mock(Request.class);
        @SuppressWarnings("unchecked")
        Request<?, EthBlock> blockRequest = Mockito.mock(Request.class);
        @SuppressWarnings("unchecked")
        Request<?, EthGetTransactionReceipt> receiptRequest = Mockito.mock(Request.class);

        EthBlockNumber blockNumberResponse = new EthBlockNumber();
        blockNumberResponse.setResult("0x10");
        EthBlock ethBlock = Mockito.mock(EthBlock.class);
        EthBlock.Block block = Mockito.mock(EthBlock.Block.class);
        EthBlock.TransactionObject transactionObject = Mockito.mock(EthBlock.TransactionObject.class);
        EthGetTransactionReceipt receiptResponse = Mockito.mock(EthGetTransactionReceipt.class);
        TransactionReceipt transactionReceipt = Mockito.mock(TransactionReceipt.class);

        when(walletBlockchainClientFactory.createEvmClient(URI.create("https://rpc.example.org"))).thenReturn(web3j);
        when(walletChainCursorRepository.findByChain(ChainType.ETH)).thenReturn(new WalletChainCursor(
                1L,
                ChainType.ETH,
                "block",
                "15",
                OffsetDateTime.now()
        ));
        doReturn(blockNumberRequest).when(web3j).ethBlockNumber();
        when(blockNumberRequest.send()).thenReturn(blockNumberResponse);
        doReturn(blockRequest).when(web3j).ethGetBlockByNumber(any(), eq(true));
        when(blockRequest.send()).thenReturn(ethBlock);
        when(ethBlock.getBlock()).thenReturn(block);
        when(block.getTransactions()).thenReturn(List.of((EthBlock.TransactionResult<?>) () -> transactionObject));
        when(transactionObject.getTo()).thenReturn("0xplatform");
        when(transactionObject.getFrom()).thenReturn("0xsource");
        when(transactionObject.getHash()).thenReturn("0xabc");
        when(transactionObject.getValue()).thenReturn(new BigInteger("1000000000000000000"));
        when(transactionObject.getBlockNumber()).thenReturn(BigInteger.valueOf(16));
        when(walletAddressRepository.findAssignedAddressesByChain(ChainType.ETH)).thenReturn(Set.of("0xplatform"));
        doReturn(receiptRequest).when(web3j).ethGetTransactionReceipt("0xabc");
        when(receiptRequest.send()).thenReturn(receiptResponse);
        when(receiptResponse.getTransactionReceipt()).thenReturn(Optional.of(transactionReceipt));
        when(transactionReceipt.isStatusOK()).thenReturn(true);
        when(walletDepositTransactionRepository.findByChainAndBlockRange(ChainType.ETH, 16L, 16L)).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Consumer<ObservedDepositTransaction> depositConsumer = Mockito.mock(Consumer.class);
        Web3jChainDepositListener listener = new Web3jChainDepositListener(
                ChainType.ETH,
                new WalletServiceProperties.Chain(
                        URI.create("https://rpc.example.org"),
                        Duration.ofDays(1),
                        1,
                        "block",
                        "0"
                ),
                walletBlockchainClientFactory,
                walletAddressRepository,
                walletChainCursorRepository,
                walletDepositTransactionRepository,
                meterRegistry
        );
        try {
            listener.start(depositConsumer);

            Timer scanTimer = meterRegistry.find("falconx.wallet.chain.scan.duration")
                    .tag("chain", "eth")
                    .timer();
            Assertions.assertNotNull(scanTimer, "应记录 ETH 扫块 Timer");
            // start() 同步触发 bootstrap + poll 扫块，均计时；本轮真实扫块至少 1 次。
            Assertions.assertTrue(scanTimer.count() >= 1, "ETH 扫块 Timer 应至少记录 1 次");
            Assertions.assertTrue(scanTimer.totalTime(TimeUnit.NANOSECONDS) > 0, "ETH 扫块 Timer 应有非零耗时");

            Counter detectedCounter = meterRegistry.find("falconx.wallet.deposit.detected.total")
                    .tag("chain", "eth")
                    .counter();
            Assertions.assertNotNull(detectedCounter, "检测到入金后应有 ETH detected Counter");
            Assertions.assertEquals(1.0, detectedCounter.count(), "检测到 1 笔 ETH 入金，count 应为 1");
        } finally {
            listener.destroy();
        }
    }

    @Test
    void shouldRecordScanDurationAndDetectedCounterForTronTrc20Deposit() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        String platformAddress = tronBase58(RAW_TO);
        WalletServiceProperties.Chain chain = new WalletServiceProperties.Chain(
                URI.create("https://tron-test.example.org"),
                Duration.ofDays(1),
                1,
                "block",
                "0"
        );
        chain.setUsdtContract(tronBase58(RAW_CONTRACT));
        chain.setUsdtDecimals(6);

        WalletBlockchainClientFactory walletBlockchainClientFactory = Mockito.mock(WalletBlockchainClientFactory.class);
        WalletAddressRepository walletAddressRepository = Mockito.mock(WalletAddressRepository.class);
        WalletChainCursorRepository walletChainCursorRepository = Mockito.mock(WalletChainCursorRepository.class);
        WalletDepositTransactionRepository walletDepositTransactionRepository =
                Mockito.mock(WalletDepositTransactionRepository.class);
        TronChainClient tronChainClient = Mockito.mock(TronChainClient.class);

        when(walletBlockchainClientFactory.createTronClient(chain)).thenReturn(tronChainClient);
        when(walletChainCursorRepository.findByChain(ChainType.TRON)).thenReturn(new WalletChainCursor(
                1L,
                ChainType.TRON,
                "block",
                "99",
                OffsetDateTime.now()
        ));
        when(tronChainClient.fetchLatestBlockNumber()).thenReturn(100L);
        when(walletAddressRepository.findAssignedAddressesByChain(ChainType.TRON)).thenReturn(Set.of(platformAddress));
        TronChainClient.TronTransactionInfo transactionInfo = new TronChainClient.TronTransactionInfo(
                "0xtrontx",
                100L,
                List.of(new TronChainClient.TronLog(
                        RAW_CONTRACT,
                        List.of(TRC20_TRANSFER_TOPIC, indexedTopic(RAW_FROM), indexedTopic(RAW_TO)),
                        uint256Hex(new BigInteger("100000000"))
                ))
        );
        when(tronChainClient.fetchTransactionInfosByBlockNumber(100L)).thenReturn(List.of(transactionInfo));
        when(walletDepositTransactionRepository.findByChainAndBlockRange(ChainType.TRON, 100L, 100L)).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Consumer<ObservedDepositTransaction> depositConsumer = Mockito.mock(Consumer.class);
        TronApiChainDepositListener listener = new TronApiChainDepositListener(
                chain,
                walletBlockchainClientFactory,
                walletAddressRepository,
                walletChainCursorRepository,
                walletDepositTransactionRepository,
                meterRegistry
        );
        try {
            listener.start(depositConsumer);

            Timer scanTimer = meterRegistry.find("falconx.wallet.chain.scan.duration")
                    .tag("chain", "tron")
                    .timer();
            Assertions.assertNotNull(scanTimer, "应记录 TRON 扫块 Timer");
            Assertions.assertTrue(scanTimer.count() >= 1, "TRON 扫块 Timer 应至少记录 1 次");
            Assertions.assertTrue(scanTimer.totalTime(TimeUnit.NANOSECONDS) > 0, "TRON 扫块 Timer 应有非零耗时");

            Counter detectedCounter = meterRegistry.find("falconx.wallet.deposit.detected.total")
                    .tag("chain", "tron")
                    .counter();
            Assertions.assertNotNull(detectedCounter, "检测到入金后应有 TRON detected Counter");
            Assertions.assertEquals(1.0, detectedCounter.count(), "检测到 1 笔 TRON 入金，count 应为 1");
        } finally {
            listener.destroy();
        }
    }

    private static String indexedTopic(String raw20Address) {
        return "000000000000000000000000" + raw20Address;
    }

    private static String uint256Hex(BigInteger value) {
        return Numeric.toHexStringNoPrefixZeroPadded(value, 64);
    }

    private static String tronBase58(String raw20Address) {
        byte[] raw20 = Numeric.hexStringToByteArray(raw20Address);
        byte[] tronAddress = new byte[21];
        tronAddress[0] = 0x41;
        System.arraycopy(raw20, 0, tronAddress, 1, raw20.length);
        return Base58Check.bytesToBase58(tronAddress);
    }
}
