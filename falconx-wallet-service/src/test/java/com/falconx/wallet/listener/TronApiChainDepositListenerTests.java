package com.falconx.wallet.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.domain.enums.ChainType;
import com.falconx.wallet.client.TronChainClient;
import com.falconx.wallet.client.WalletBlockchainClientFactory;
import com.falconx.wallet.config.WalletServiceProperties;
import com.falconx.wallet.entity.WalletChainCursor;
import com.falconx.wallet.repository.WalletAddressRepository;
import com.falconx.wallet.repository.WalletChainCursorRepository;
import com.falconx.wallet.repository.WalletDepositTransactionRepository;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.tron.trident.utils.Base58Check;
import org.tron.trident.utils.Numeric;

/**
 * {@link TronApiChainDepositListener} TRC20 扫块监听测试。
 */
class TronApiChainDepositListenerTests {

    private static final String RAW_CONTRACT = "a614f803b6fd780986a42c78ec9c7f77e6ded13c";
    private static final String RAW_FROM = "79309abcff2cf531070ca9222a1f72c4a5136874";
    private static final String RAW_TO = "81b64b1c09d448d25c9eeb3ee3b8f3348a694c96";
    private static final String TRANSFER_TOPIC =
            "ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    @Test
    void shouldPrepareBootstrapBaselineWhenCursorStillInitialValue() throws Exception {
        WalletServiceProperties.Chain chain = chainProperties();
        FakeTronChainClient tronClient = new FakeTronChainClient(100L);
        WalletBlockchainClientFactory walletBlockchainClientFactory = Mockito.mock(WalletBlockchainClientFactory.class);
        WalletAddressRepository walletAddressRepository = Mockito.mock(WalletAddressRepository.class);
        WalletChainCursorRepository walletChainCursorRepository = Mockito.mock(WalletChainCursorRepository.class);
        WalletDepositTransactionRepository walletDepositTransactionRepository = Mockito.mock(WalletDepositTransactionRepository.class);
        when(walletBlockchainClientFactory.createTronClient(chain)).thenReturn(tronClient);
        when(walletChainCursorRepository.findByChain(ChainType.TRON)).thenReturn(new WalletChainCursor(
                1L,
                ChainType.TRON,
                "block",
                "0",
                OffsetDateTime.now()
        ));

        @SuppressWarnings("unchecked")
        Consumer<ObservedDepositTransaction> depositConsumer = Mockito.mock(Consumer.class);
        TronApiChainDepositListener listener = createListener(
                chain,
                walletBlockchainClientFactory,
                walletAddressRepository,
                walletChainCursorRepository,
                walletDepositTransactionRepository
        );
        try {
            listener.start(depositConsumer);

            verify(walletChainCursorRepository).updateCursor(ChainType.TRON, "100");
            verify(depositConsumer, never()).accept(any());
        } finally {
            listener.destroy();
        }
    }

    @Test
    void shouldObserveTrc20TransferForAssignedPlatformAddress() throws Exception {
        WalletServiceProperties.Chain chain = chainProperties();
        String platformAddress = tronBase58(RAW_TO);
        FakeTronChainClient tronClient = new FakeTronChainClient(100L);
        tronClient.addTransactionInfo(100L, new TronChainClient.TronTransactionInfo(
                "0xabc",
                100L,
                List.of(new TronChainClient.TronLog(
                        RAW_CONTRACT,
                        List.of(TRANSFER_TOPIC, indexedTopic(RAW_FROM), indexedTopic(RAW_TO)),
                        uint256Hex(new BigInteger("100000000"))
                ))
        ));
        WalletBlockchainClientFactory walletBlockchainClientFactory = Mockito.mock(WalletBlockchainClientFactory.class);
        WalletAddressRepository walletAddressRepository = Mockito.mock(WalletAddressRepository.class);
        WalletChainCursorRepository walletChainCursorRepository = Mockito.mock(WalletChainCursorRepository.class);
        WalletDepositTransactionRepository walletDepositTransactionRepository = Mockito.mock(WalletDepositTransactionRepository.class);
        when(walletBlockchainClientFactory.createTronClient(chain)).thenReturn(tronClient);
        when(walletChainCursorRepository.findByChain(ChainType.TRON)).thenReturn(new WalletChainCursor(
                1L,
                ChainType.TRON,
                "block",
                "99",
                OffsetDateTime.now()
        ));
        when(walletAddressRepository.findAssignedAddressesByChain(ChainType.TRON)).thenReturn(Set.of(platformAddress));
        when(walletDepositTransactionRepository.findByChainAndBlockRange(ChainType.TRON, 100L, 100L)).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Consumer<ObservedDepositTransaction> depositConsumer = Mockito.mock(Consumer.class);
        TronApiChainDepositListener listener = createListener(
                chain,
                walletBlockchainClientFactory,
                walletAddressRepository,
                walletChainCursorRepository,
                walletDepositTransactionRepository
        );
        try {
            listener.start(depositConsumer);

            ArgumentCaptor<ObservedDepositTransaction> observedCaptor =
                    ArgumentCaptor.forClass(ObservedDepositTransaction.class);
            verify(depositConsumer).accept(observedCaptor.capture());
            verify(walletChainCursorRepository).updateCursor(ChainType.TRON, "100");

            ObservedDepositTransaction observed = observedCaptor.getValue();
            Assertions.assertEquals(ChainType.TRON, observed.chain());
            Assertions.assertEquals("USDT", observed.token());
            Assertions.assertEquals(tronBase58(RAW_CONTRACT), observed.tokenContractAddress());
            Assertions.assertEquals("0xabc", observed.txHash());
            Assertions.assertEquals(0, observed.logIndex());
            Assertions.assertEquals(tronBase58(RAW_FROM), observed.fromAddress());
            Assertions.assertEquals(platformAddress, observed.toAddress());
            Assertions.assertEquals(new BigDecimal("100.00000000"), observed.amount());
            Assertions.assertEquals(100L, observed.blockNumber());
            Assertions.assertEquals(1, observed.confirmations());
            Assertions.assertFalse(observed.reversed());
        } finally {
            listener.destroy();
        }
    }

    private TronApiChainDepositListener createListener(WalletServiceProperties.Chain chain,
                                                       WalletBlockchainClientFactory walletBlockchainClientFactory,
                                                       WalletAddressRepository walletAddressRepository,
                                                       WalletChainCursorRepository walletChainCursorRepository,
                                                       WalletDepositTransactionRepository walletDepositTransactionRepository) {
        return new TronApiChainDepositListener(
                chain,
                walletBlockchainClientFactory,
                walletAddressRepository,
                walletChainCursorRepository,
                walletDepositTransactionRepository
        );
    }

    private WalletServiceProperties.Chain chainProperties() {
        WalletServiceProperties.Chain chain = new WalletServiceProperties.Chain(
                URI.create("https://tron-test.example.org"),
                Duration.ofDays(1),
                1,
                "block",
                "0"
        );
        chain.setUsdtContract(tronBase58(RAW_CONTRACT));
        chain.setUsdtDecimals(6);
        return chain;
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

    private static final class FakeTronChainClient implements TronChainClient {
        private final long latestBlockNumber;
        private final Map<Long, List<TronTransactionInfo>> transactionInfos = new HashMap<>();

        private FakeTronChainClient(long latestBlockNumber) {
            this.latestBlockNumber = latestBlockNumber;
        }

        private void addTransactionInfo(long blockNumber, TronTransactionInfo transactionInfo) {
            transactionInfos.computeIfAbsent(blockNumber, ignored -> new ArrayList<>()).add(transactionInfo);
        }

        @Override
        public long fetchLatestBlockNumber() {
            return latestBlockNumber;
        }

        @Override
        public List<TronTransactionInfo> fetchTransactionInfosByBlockNumber(long blockNumber) {
            return transactionInfos.getOrDefault(blockNumber, List.of());
        }
    }
}
