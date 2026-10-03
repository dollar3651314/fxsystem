package com.falconx.wallet.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.falconx.domain.enums.ChainType;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.wallet.client.WalletBlockchainClientFactory;
import com.falconx.wallet.config.WalletServiceProperties;
import com.falconx.wallet.entity.WalletChainCursor;
import com.falconx.wallet.entity.WalletDepositStatus;
import com.falconx.wallet.entity.WalletDepositTransaction;
import com.falconx.wallet.repository.WalletAddressRepository;
import com.falconx.wallet.repository.WalletChainCursorRepository;
import com.falconx.wallet.repository.WalletDepositTransactionRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

/**
 * {@link Web3jChainDepositListener} 测试。
 *
 * <p>当前只验证 Stage 6A 新补的最小真实链路：
 *
 * <ul>
 *   <li>首次启动若游标仍为初始值，只建立链头基线而不误扫全历史</li>
 *   <li>命中平台地址的原生币转账会被转成 `ObservedDepositTransaction` 并交给应用层</li>
 *   <li>链节点短时异常只记录告警，不把启动流程升级成失败</li>
 * </ul>
 */
@ExtendWith(OutputCaptureExtension.class)
class Web3jChainDepositListenerTests {

    @Test
    void shouldPrepareBootstrapBaselineWhenCursorStillInitialValue() throws Exception {
        WalletBlockchainClientFactory walletBlockchainClientFactory = Mockito.mock(WalletBlockchainClientFactory.class);
        WalletAddressRepository walletAddressRepository = Mockito.mock(WalletAddressRepository.class);
        WalletChainCursorRepository walletChainCursorRepository = Mockito.mock(WalletChainCursorRepository.class);
        WalletDepositTransactionRepository walletDepositTransactionRepository = Mockito.mock(WalletDepositTransactionRepository.class);
        Web3j web3j = Mockito.mock(Web3j.class);
        @SuppressWarnings("unchecked")
        Request<?, EthBlockNumber> blockNumberRequest = Mockito.mock(Request.class);
        EthBlockNumber response = new EthBlockNumber();
        response.setResult("0x10");
        when(walletBlockchainClientFactory.createEvmClient(URI.create("https://rpc.example.org"))).thenReturn(web3j);
        when(walletChainCursorRepository.findByChain(ChainType.ETH)).thenReturn(new WalletChainCursor(
                1L,
                ChainType.ETH,
                "block",
                "0",
                OffsetDateTime.now()
        ));
        doReturn(blockNumberRequest).when(web3j).ethBlockNumber();
        when(blockNumberRequest.send()).thenReturn(response);

        @SuppressWarnings("unchecked")
        Consumer<ObservedDepositTransaction> depositConsumer = Mockito.mock(Consumer.class);
        Web3jChainDepositListener listener = createListener(
                URI.create("https://rpc.example.org"),
                walletBlockchainClientFactory,
                walletAddressRepository,
                walletChainCursorRepository,
                walletDepositTransactionRepository,
                Duration.ofDays(1),
                12,
                "0"
        );
        try {
            listener.start(depositConsumer);
            verify(walletChainCursorRepository).updateCursor(ChainType.ETH, "16");
            verify(depositConsumer, never()).accept(any());
        } finally {
            listener.destroy();
        }
    }

    @Test
    void shouldObserveNativeTransferForAssignedPlatformAddress(CapturedOutput output) throws Exception {
        ListAppender<ILoggingEvent> logAppender = attachLogAppender();
        WalletBlockchainClientFactory walletBlockchainClientFactory = Mockito.mock(WalletBlockchainClientFactory.class);
        WalletAddressRepository walletAddressRepository = Mockito.mock(WalletAddressRepository.class);
        WalletChainCursorRepository walletChainCursorRepository = Mockito.mock(WalletChainCursorRepository.class);
        WalletDepositTransactionRepository walletDepositTransactionRepository = Mockito.mock(WalletDepositTransactionRepository.class);
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
        when(transactionObject.getValue()).thenReturn(new java.math.BigInteger("1000000000000000000"));
        when(transactionObject.getBlockNumber()).thenReturn(java.math.BigInteger.valueOf(16));
        when(walletAddressRepository.findAssignedAddressesByChain(ChainType.ETH)).thenReturn(Set.of("0xplatform"));
        doReturn(receiptRequest).when(web3j).ethGetTransactionReceipt("0xabc");
        when(receiptRequest.send()).thenReturn(receiptResponse);
        when(receiptResponse.getTransactionReceipt()).thenReturn(Optional.of(transactionReceipt));
        when(transactionReceipt.isStatusOK()).thenReturn(true);
        when(walletDepositTransactionRepository.findByChainAndBlockRange(ChainType.ETH, 16L, 16L)).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Consumer<ObservedDepositTransaction> depositConsumer = Mockito.mock(Consumer.class);
        Web3jChainDepositListener listener = createListener(
                URI.create("https://rpc.example.org"),
                walletBlockchainClientFactory,
                walletAddressRepository,
                walletChainCursorRepository,
                walletDepositTransactionRepository,
                Duration.ofDays(1),
                1,
                "0"
        );
        try {
            listener.start(depositConsumer);

            ArgumentCaptor<ObservedDepositTransaction> observedCaptor = ArgumentCaptor.forClass(ObservedDepositTransaction.class);
            verify(depositConsumer).accept(observedCaptor.capture());
            verify(walletChainCursorRepository).updateCursor(ChainType.ETH, "16");

            ObservedDepositTransaction observedDeposit = observedCaptor.getValue();
            org.junit.jupiter.api.Assertions.assertEquals(ChainType.ETH, observedDeposit.chain());
            org.junit.jupiter.api.Assertions.assertEquals("ETH", observedDeposit.token());
            org.junit.jupiter.api.Assertions.assertEquals("0xabc", observedDeposit.txHash());
            org.junit.jupiter.api.Assertions.assertEquals("0xplatform", observedDeposit.toAddress());
            org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("1.00000000"), observedDeposit.amount());
            org.junit.jupiter.api.Assertions.assertEquals(1, observedDeposit.confirmations());
            verify(walletAddressRepository).findAssignedAddressesByChain(ChainType.ETH);
            org.junit.jupiter.api.Assertions.assertTrue(output.toString().contains("wallet.listener.chainHead.synced chain=ETH"));
            org.junit.jupiter.api.Assertions.assertTrue(output.toString().contains("scannedBlocks=1"));
            org.junit.jupiter.api.Assertions.assertTrue(output.toString().contains("detectedCount=1"));
            org.junit.jupiter.api.Assertions.assertTrue(output.toString().contains("reversedCount=0"));
            assertTraceIdPresent(logAppender, "wallet.listener.chainHead.synced");
        } finally {
            detachLogAppender(logAppender);
            listener.destroy();
        }
    }

    @Test
    void shouldKeepServiceAliveWhenChainHeadSyncFails(CapturedOutput output) throws Exception {
        ListAppender<ILoggingEvent> logAppender = attachLogAppender();
        WalletBlockchainClientFactory walletBlockchainClientFactory = Mockito.mock(WalletBlockchainClientFactory.class);
        WalletAddressRepository walletAddressRepository = Mockito.mock(WalletAddressRepository.class);
        WalletChainCursorRepository walletChainCursorRepository = Mockito.mock(WalletChainCursorRepository.class);
        WalletDepositTransactionRepository walletDepositTransactionRepository = Mockito.mock(WalletDepositTransactionRepository.class);
        Web3j web3j = Mockito.mock(Web3j.class);
        @SuppressWarnings("unchecked")
        Request<?, EthBlockNumber> request = Mockito.mock(Request.class);
        when(walletBlockchainClientFactory.createEvmClient(URI.create("wss://eth-sepolia.example.org"))).thenReturn(web3j);
        when(walletChainCursorRepository.findByChain(ChainType.ETH)).thenReturn(new WalletChainCursor(
                1L,
                ChainType.ETH,
                "block",
                "15",
                OffsetDateTime.now()
        ));
        doReturn(request).when(web3j).ethBlockNumber();
        when(request.send()).thenThrow(new IOException("rpc offline"));

        @SuppressWarnings("unchecked")
        Consumer<ObservedDepositTransaction> depositConsumer = Mockito.mock(Consumer.class);
        Web3jChainDepositListener listener = createListener(
                URI.create("wss://eth-sepolia.example.org"),
                walletBlockchainClientFactory,
                walletAddressRepository,
                walletChainCursorRepository,
                walletDepositTransactionRepository,
                Duration.ofDays(1),
                12,
                "0"
        );
        try {
            listener.start(depositConsumer);
            verify(walletChainCursorRepository, never()).updateCursor(eq(ChainType.ETH), anyString());
            verify(depositConsumer, never()).accept(any());
            org.junit.jupiter.api.Assertions.assertTrue(output.toString().contains("wallet.listener.chainHead.syncFailed chain=ETH"));
            org.junit.jupiter.api.Assertions.assertTrue(output.toString().contains("scannedBlocks=0"));
            assertTraceIdPresent(logAppender, "wallet.listener.chainHead.syncFailed");
        } finally {
            detachLogAppender(logAppender);
            listener.destroy();
        }
    }

    @Test
    void shouldMaskSensitiveRpcUrlInListenerLogs(CapturedOutput output) throws Exception {
        WalletBlockchainClientFactory walletBlockchainClientFactory = Mockito.mock(WalletBlockchainClientFactory.class);
        WalletAddressRepository walletAddressRepository = Mockito.mock(WalletAddressRepository.class);
        WalletChainCursorRepository walletChainCursorRepository = Mockito.mock(WalletChainCursorRepository.class);
        WalletDepositTransactionRepository walletDepositTransactionRepository = Mockito.mock(WalletDepositTransactionRepository.class);
        Web3j web3j = Mockito.mock(Web3j.class);
        @SuppressWarnings("unchecked")
        Request<?, EthBlockNumber> request = Mockito.mock(Request.class);
        URI sensitiveRpcUrl = URI.create("wss://eth-mainnet.g.alchemy.com/v2/secret-token?apikey=secret");
        when(walletBlockchainClientFactory.createEvmClient(sensitiveRpcUrl)).thenReturn(web3j);
        when(walletChainCursorRepository.findByChain(ChainType.ETH)).thenReturn(new WalletChainCursor(
                1L,
                ChainType.ETH,
                "block",
                "15",
                OffsetDateTime.now()
        ));
        doReturn(request).when(web3j).ethBlockNumber();
        when(request.send()).thenThrow(new IOException("rpc offline"));

        @SuppressWarnings("unchecked")
        Consumer<ObservedDepositTransaction> depositConsumer = Mockito.mock(Consumer.class);
        Web3jChainDepositListener listener = createListener(
                sensitiveRpcUrl,
                walletBlockchainClientFactory,
                walletAddressRepository,
                walletChainCursorRepository,
                walletDepositTransactionRepository,
                Duration.ofDays(1),
                12,
                "0"
        );
        try {
            listener.start(depositConsumer);

            String logs = output.toString();
            org.junit.jupiter.api.Assertions.assertTrue(logs.contains("rpcUrl=wss://eth-mainnet.g.alchemy.com/v2/[REDACTED]"));
            org.junit.jupiter.api.Assertions.assertFalse(logs.contains("secret-token"));
            org.junit.jupiter.api.Assertions.assertFalse(logs.contains("apikey=secret"));
        } finally {
            listener.destroy();
        }
    }

    @Test
    void shouldEmitReversedObservationWhenConfirmedTrackedTransactionDisappearsFromRescanWindow() throws Exception {
        WalletBlockchainClientFactory walletBlockchainClientFactory = Mockito.mock(WalletBlockchainClientFactory.class);
        WalletAddressRepository walletAddressRepository = Mockito.mock(WalletAddressRepository.class);
        WalletChainCursorRepository walletChainCursorRepository = Mockito.mock(WalletChainCursorRepository.class);
        WalletDepositTransactionRepository walletDepositTransactionRepository = Mockito.mock(WalletDepositTransactionRepository.class);
        Web3j web3j = Mockito.mock(Web3j.class);
        @SuppressWarnings("unchecked")
        Request<?, EthBlockNumber> blockNumberRequest = Mockito.mock(Request.class);
        @SuppressWarnings("unchecked")
        Request<?, EthBlock> blockRequest = Mockito.mock(Request.class);

        EthBlockNumber blockNumberResponse = new EthBlockNumber();
        blockNumberResponse.setResult("0x10");
        EthBlock ethBlock = Mockito.mock(EthBlock.class);
        EthBlock.Block block = Mockito.mock(EthBlock.Block.class);

        when(walletBlockchainClientFactory.createEvmClient(URI.create("https://rpc.example.org"))).thenReturn(web3j);
        when(walletChainCursorRepository.findByChain(ChainType.ETH)).thenReturn(new WalletChainCursor(
                1L,
                ChainType.ETH,
                "block",
                "16",
                OffsetDateTime.now()
        ));
        when(walletDepositTransactionRepository.findByChainAndBlockRange(ChainType.ETH, 16L, 16L)).thenReturn(List.of(
                new WalletDepositTransaction(
                        100L,
                        200L,
                        ChainType.ETH,
                        "ETH",
                        null,
                        "0xdeadbeef",
                        0,
                        "0xsource",
                        "0xplatform",
                        new BigDecimal("1.00000000"),
                        16L,
                        12,
                        12,
                        WalletDepositStatus.CONFIRMED,
                        OffsetDateTime.now().minusMinutes(5),
                        OffsetDateTime.now().minusMinutes(4),
                        OffsetDateTime.now().minusMinutes(1)
                )
        ));
        when(walletAddressRepository.findAssignedAddressesByChain(ChainType.ETH)).thenReturn(Set.of("0xplatform"));
        doReturn(blockNumberRequest).when(web3j).ethBlockNumber();
        when(blockNumberRequest.send()).thenReturn(blockNumberResponse);
        doReturn(blockRequest).when(web3j).ethGetBlockByNumber(any(), eq(true));
        when(blockRequest.send()).thenReturn(ethBlock);
        when(ethBlock.getBlock()).thenReturn(block);
        when(block.getTransactions()).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Consumer<ObservedDepositTransaction> depositConsumer = Mockito.mock(Consumer.class);
        Web3jChainDepositListener listener = createListener(
                URI.create("https://rpc.example.org"),
                walletBlockchainClientFactory,
                walletAddressRepository,
                walletChainCursorRepository,
                walletDepositTransactionRepository,
                Duration.ofDays(1),
                1,
                "0"
        );
        try {
            listener.start(depositConsumer);

            ArgumentCaptor<ObservedDepositTransaction> observedCaptor = ArgumentCaptor.forClass(ObservedDepositTransaction.class);
            verify(depositConsumer).accept(observedCaptor.capture());
            ObservedDepositTransaction observedDeposit = observedCaptor.getValue();
            org.junit.jupiter.api.Assertions.assertEquals("0xdeadbeef", observedDeposit.txHash());
            org.junit.jupiter.api.Assertions.assertTrue(observedDeposit.reversed());
            org.junit.jupiter.api.Assertions.assertEquals(0, observedDeposit.confirmations());
            verify(walletChainCursorRepository).updateCursor(ChainType.ETH, "16");
        } finally {
            listener.destroy();
        }
    }

    @Test
    void shouldObserveErc20TransferForAssignedPlatformAddress() throws Exception {
        String platformAddress = "0x00000000000000000000000000000000a11ce001";
        String sourceAddress = "0x00000000000000000000000000000000b0b00001";
        String tokenContractAddress = "0x00000000000000000000000000000000c0ffee01";
        WalletBlockchainClientFactory walletBlockchainClientFactory = Mockito.mock(WalletBlockchainClientFactory.class);
        WalletAddressRepository walletAddressRepository = Mockito.mock(WalletAddressRepository.class);
        WalletChainCursorRepository walletChainCursorRepository = Mockito.mock(WalletChainCursorRepository.class);
        WalletDepositTransactionRepository walletDepositTransactionRepository = Mockito.mock(WalletDepositTransactionRepository.class);
        Web3j web3j = Mockito.mock(Web3j.class);
        @SuppressWarnings("unchecked")
        Request<?, EthBlockNumber> blockNumberRequest = Mockito.mock(Request.class);
        @SuppressWarnings("unchecked")
        Request<?, EthBlock> blockRequest = Mockito.mock(Request.class);
        @SuppressWarnings("unchecked")
        Request<?, EthLog> logRequest = Mockito.mock(Request.class);
        @SuppressWarnings("unchecked")
        Request<?, EthCall> decimalsRequest = Mockito.mock(Request.class);
        @SuppressWarnings("unchecked")
        Request<?, EthCall> symbolRequest = Mockito.mock(Request.class);

        EthBlockNumber blockNumberResponse = new EthBlockNumber();
        blockNumberResponse.setResult("0x10");
        EthBlock ethBlock = Mockito.mock(EthBlock.class);
        EthBlock.Block block = Mockito.mock(EthBlock.Block.class);
        EthBlock.TransactionObject transactionObject = Mockito.mock(EthBlock.TransactionObject.class);
        EthLog logResponse = ethLogResponse(erc20TransferLog(
                "0xerc20tx",
                tokenContractAddress,
                sourceAddress,
                platformAddress,
                7,
                new BigInteger("1500000"),
                16L
        ));
        EthCall decimalsResponse = new EthCall();
        decimalsResponse.setResult("0x0000000000000000000000000000000000000000000000000000000000000006");
        EthCall symbolResponse = new EthCall();
        symbolResponse.setResult("0x5553445400000000000000000000000000000000000000000000000000000000");

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
        when(transactionObject.getHash()).thenReturn("0xerc20tx");
        when(transactionObject.getTo()).thenReturn(tokenContractAddress);
        when(transactionObject.getFrom()).thenReturn("0x00000000000000000000000000000000decaf001");
        when(transactionObject.getInput()).thenReturn("0xa9059cbb");
        when(transactionObject.getValue()).thenReturn(BigInteger.ZERO);
        when(transactionObject.getBlockNumber()).thenReturn(BigInteger.valueOf(16));
        when(walletAddressRepository.findAssignedAddressesByChain(ChainType.ETH)).thenReturn(Set.of(platformAddress));
        doReturn(logRequest).when(web3j).ethGetLogs(any(EthFilter.class));
        when(logRequest.send()).thenReturn(logResponse);
        doReturn(decimalsRequest, symbolRequest).when(web3j).ethCall(any(), eq(DefaultBlockParameterName.LATEST));
        when(decimalsRequest.send()).thenReturn(decimalsResponse);
        when(symbolRequest.send()).thenReturn(symbolResponse);
        when(walletDepositTransactionRepository.findByChainAndBlockRange(ChainType.ETH, 16L, 16L)).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Consumer<ObservedDepositTransaction> depositConsumer = Mockito.mock(Consumer.class);
        Web3jChainDepositListener listener = createListener(
                URI.create("https://rpc.example.org"),
                walletBlockchainClientFactory,
                walletAddressRepository,
                walletChainCursorRepository,
                walletDepositTransactionRepository,
                Duration.ofDays(1),
                1,
                "0"
        );
        try {
            listener.start(depositConsumer);

            ArgumentCaptor<ObservedDepositTransaction> observedCaptor = ArgumentCaptor.forClass(ObservedDepositTransaction.class);
            verify(depositConsumer).accept(observedCaptor.capture());
            ObservedDepositTransaction observedDeposit = observedCaptor.getValue();

            org.junit.jupiter.api.Assertions.assertEquals("USDT", observedDeposit.token());
            org.junit.jupiter.api.Assertions.assertEquals(tokenContractAddress, observedDeposit.tokenContractAddress());
            org.junit.jupiter.api.Assertions.assertEquals(7, observedDeposit.logIndex());
            org.junit.jupiter.api.Assertions.assertEquals(sourceAddress, observedDeposit.fromAddress());
            org.junit.jupiter.api.Assertions.assertEquals(platformAddress, observedDeposit.toAddress());
            org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("1.50000000"), observedDeposit.amount());
            org.junit.jupiter.api.Assertions.assertEquals(1, observedDeposit.confirmations());
            verify(walletChainCursorRepository).updateCursor(ChainType.ETH, "16");
        } finally {
            listener.destroy();
        }
    }

    @Test
    void shouldQueryErc20TransfersByLogsWithoutFetchingContractTransactionReceipts() throws Exception {
        String platformAddress = "0x00000000000000000000000000000000a11ce001";
        String sourceAddress = "0x00000000000000000000000000000000b0b00001";
        String tokenContractAddress = "0x00000000000000000000000000000000c0ffee01";
        WalletBlockchainClientFactory walletBlockchainClientFactory = Mockito.mock(WalletBlockchainClientFactory.class);
        WalletAddressRepository walletAddressRepository = Mockito.mock(WalletAddressRepository.class);
        WalletChainCursorRepository walletChainCursorRepository = Mockito.mock(WalletChainCursorRepository.class);
        WalletDepositTransactionRepository walletDepositTransactionRepository = Mockito.mock(WalletDepositTransactionRepository.class);
        Web3j web3j = Mockito.mock(Web3j.class);
        @SuppressWarnings("unchecked")
        Request<?, EthBlockNumber> blockNumberRequest = Mockito.mock(Request.class);
        @SuppressWarnings("unchecked")
        Request<?, EthBlock> blockRequest = Mockito.mock(Request.class);
        @SuppressWarnings("unchecked")
        Request<?, EthLog> logRequest = Mockito.mock(Request.class);
        @SuppressWarnings("unchecked")
        Request<?, EthCall> decimalsRequest = Mockito.mock(Request.class);
        @SuppressWarnings("unchecked")
        Request<?, EthCall> symbolRequest = Mockito.mock(Request.class);

        EthBlockNumber blockNumberResponse = new EthBlockNumber();
        blockNumberResponse.setResult("0x10");
        EthBlock ethBlock = Mockito.mock(EthBlock.class);
        EthBlock.Block block = Mockito.mock(EthBlock.Block.class);
        EthBlock.TransactionObject noisyContractTransaction = Mockito.mock(EthBlock.TransactionObject.class);
        Log transferLog = erc20TransferLog(
                "0xerc20logtx",
                tokenContractAddress,
                sourceAddress,
                platformAddress,
                7,
                new BigInteger("1500000"),
                16L
        );
        EthLog logResponse = ethLogResponse(transferLog);
        EthCall decimalsResponse = new EthCall();
        decimalsResponse.setResult("0x0000000000000000000000000000000000000000000000000000000000000006");
        EthCall symbolResponse = new EthCall();
        symbolResponse.setResult("0x5553445400000000000000000000000000000000000000000000000000000000");

        when(walletBlockchainClientFactory.createEvmClient(URI.create("https://rpc.example.org"))).thenReturn(web3j);
        when(walletChainCursorRepository.findByChain(ChainType.ETH)).thenReturn(new WalletChainCursor(
                1L,
                ChainType.ETH,
                "block",
                "15",
                OffsetDateTime.now()
        ));
        when(walletAddressRepository.findAssignedAddressesByChain(ChainType.ETH)).thenReturn(Set.of(platformAddress));
        when(walletDepositTransactionRepository.findByChainAndBlockRange(ChainType.ETH, 16L, 16L)).thenReturn(List.of());
        doReturn(blockNumberRequest).when(web3j).ethBlockNumber();
        when(blockNumberRequest.send()).thenReturn(blockNumberResponse);
        doReturn(blockRequest).when(web3j).ethGetBlockByNumber(any(), eq(true));
        when(blockRequest.send()).thenReturn(ethBlock);
        when(ethBlock.getBlock()).thenReturn(block);
        when(block.getTransactions()).thenReturn(List.of((EthBlock.TransactionResult<?>) () -> noisyContractTransaction));
        when(noisyContractTransaction.getHash()).thenReturn("0xnoisy-contract");
        when(noisyContractTransaction.getTo()).thenReturn(tokenContractAddress);
        when(noisyContractTransaction.getInput()).thenReturn("0xa9059cbb");
        when(noisyContractTransaction.getValue()).thenReturn(BigInteger.ZERO);
        when(noisyContractTransaction.getBlockNumber()).thenReturn(BigInteger.valueOf(16));
        doReturn(logRequest).when(web3j).ethGetLogs(any(EthFilter.class));
        when(logRequest.send()).thenReturn(logResponse);
        doReturn(decimalsRequest, symbolRequest).when(web3j).ethCall(any(), eq(DefaultBlockParameterName.LATEST));
        when(decimalsRequest.send()).thenReturn(decimalsResponse);
        when(symbolRequest.send()).thenReturn(symbolResponse);

        @SuppressWarnings("unchecked")
        Consumer<ObservedDepositTransaction> depositConsumer = Mockito.mock(Consumer.class);
        Web3jChainDepositListener listener = createListener(
                URI.create("https://rpc.example.org"),
                walletBlockchainClientFactory,
                walletAddressRepository,
                walletChainCursorRepository,
                walletDepositTransactionRepository,
                Duration.ofDays(1),
                1,
                "0"
        );
        try {
            listener.start(depositConsumer);

            ArgumentCaptor<ObservedDepositTransaction> observedCaptor = ArgumentCaptor.forClass(ObservedDepositTransaction.class);
            verify(depositConsumer).accept(observedCaptor.capture());
            ObservedDepositTransaction observedDeposit = observedCaptor.getValue();

            org.junit.jupiter.api.Assertions.assertEquals("0xerc20logtx", observedDeposit.txHash());
            org.junit.jupiter.api.Assertions.assertEquals("USDT", observedDeposit.token());
            org.junit.jupiter.api.Assertions.assertEquals(tokenContractAddress, observedDeposit.tokenContractAddress());
            org.junit.jupiter.api.Assertions.assertEquals(platformAddress, observedDeposit.toAddress());
            org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("1.50000000"), observedDeposit.amount());
            verify(web3j).ethGetLogs(any(EthFilter.class));
            verify(web3j, never()).ethGetTransactionReceipt("0xnoisy-contract");
        } finally {
            listener.destroy();
        }
    }

    @Test
    void shouldReverseTrackedErc20LogWhenSameTransactionStillExistsButLogIndexDisappears() throws Exception {
        String platformAddress = "0x00000000000000000000000000000000a11ce001";
        String sourceAddress = "0x00000000000000000000000000000000b0b00001";
        String tokenContractAddress = "0x00000000000000000000000000000000c0ffee01";
        String otherAddress = "0x00000000000000000000000000000000d15ea5e1";
        WalletBlockchainClientFactory walletBlockchainClientFactory = Mockito.mock(WalletBlockchainClientFactory.class);
        WalletAddressRepository walletAddressRepository = Mockito.mock(WalletAddressRepository.class);
        WalletChainCursorRepository walletChainCursorRepository = Mockito.mock(WalletChainCursorRepository.class);
        WalletDepositTransactionRepository walletDepositTransactionRepository = Mockito.mock(WalletDepositTransactionRepository.class);
        Web3j web3j = Mockito.mock(Web3j.class);
        @SuppressWarnings("unchecked")
        Request<?, EthBlockNumber> blockNumberRequest = Mockito.mock(Request.class);
        @SuppressWarnings("unchecked")
        Request<?, EthBlock> blockRequest = Mockito.mock(Request.class);
        @SuppressWarnings("unchecked")
        Request<?, EthLog> logRequest = Mockito.mock(Request.class);

        EthBlockNumber blockNumberResponse = new EthBlockNumber();
        blockNumberResponse.setResult("0x10");
        EthBlock ethBlock = Mockito.mock(EthBlock.class);
        EthBlock.Block block = Mockito.mock(EthBlock.Block.class);
        EthBlock.TransactionObject transactionObject = Mockito.mock(EthBlock.TransactionObject.class);
        EthLog logResponse = ethLogResponse();

        when(walletBlockchainClientFactory.createEvmClient(URI.create("https://rpc.example.org"))).thenReturn(web3j);
        when(walletChainCursorRepository.findByChain(ChainType.ETH)).thenReturn(new WalletChainCursor(
                1L,
                ChainType.ETH,
                "block",
                "16",
                OffsetDateTime.now()
        ));
        when(walletDepositTransactionRepository.findByChainAndBlockRange(ChainType.ETH, 16L, 16L)).thenReturn(List.of(
                new WalletDepositTransaction(
                        100L,
                        200L,
                        ChainType.ETH,
                        "USDT",
                        tokenContractAddress,
                        "0xmultilog",
                        7,
                        sourceAddress,
                        platformAddress,
                        new BigDecimal("1.00000000"),
                        16L,
                        12,
                        12,
                        WalletDepositStatus.CONFIRMED,
                        OffsetDateTime.now().minusMinutes(5),
                        OffsetDateTime.now().minusMinutes(4),
                        OffsetDateTime.now().minusMinutes(1)
                )
        ));
        when(walletAddressRepository.findAssignedAddressesByChain(ChainType.ETH)).thenReturn(Set.of(platformAddress));
        doReturn(blockNumberRequest).when(web3j).ethBlockNumber();
        when(blockNumberRequest.send()).thenReturn(blockNumberResponse);
        doReturn(blockRequest).when(web3j).ethGetBlockByNumber(any(), eq(true));
        when(blockRequest.send()).thenReturn(ethBlock);
        when(ethBlock.getBlock()).thenReturn(block);
        when(block.getTransactions()).thenReturn(List.of((EthBlock.TransactionResult<?>) () -> transactionObject));
        when(transactionObject.getHash()).thenReturn("0xmultilog");
        when(transactionObject.getTo()).thenReturn(tokenContractAddress);
        when(transactionObject.getInput()).thenReturn("0xa9059cbb");
        when(transactionObject.getValue()).thenReturn(BigInteger.ZERO);
        when(transactionObject.getBlockNumber()).thenReturn(BigInteger.valueOf(16));
        doReturn(logRequest).when(web3j).ethGetLogs(any(EthFilter.class));
        when(logRequest.send()).thenReturn(logResponse);

        @SuppressWarnings("unchecked")
        Consumer<ObservedDepositTransaction> depositConsumer = Mockito.mock(Consumer.class);
        Web3jChainDepositListener listener = createListener(
                URI.create("https://rpc.example.org"),
                walletBlockchainClientFactory,
                walletAddressRepository,
                walletChainCursorRepository,
                walletDepositTransactionRepository,
                Duration.ofDays(1),
                1,
                "0"
        );
        try {
            listener.start(depositConsumer);

            ArgumentCaptor<ObservedDepositTransaction> observedCaptor = ArgumentCaptor.forClass(ObservedDepositTransaction.class);
            verify(depositConsumer).accept(observedCaptor.capture());
            ObservedDepositTransaction observedDeposit = observedCaptor.getValue();

            org.junit.jupiter.api.Assertions.assertTrue(observedDeposit.reversed());
            org.junit.jupiter.api.Assertions.assertEquals("0xmultilog", observedDeposit.txHash());
            org.junit.jupiter.api.Assertions.assertEquals(7, observedDeposit.logIndex());
            verify(walletChainCursorRepository).updateCursor(ChainType.ETH, "16");
        } finally {
            listener.destroy();
        }
    }

    @Test
    void shouldSkipSingleErc20MetadataFailureWithoutBreakingWholeSync() throws Exception {
        String platformAddress = "0x00000000000000000000000000000000a11ce001";
        String sourceAddress = "0x00000000000000000000000000000000b0b00001";
        String badTokenAddress = "0x00000000000000000000000000000000bad70001";
        WalletBlockchainClientFactory walletBlockchainClientFactory = Mockito.mock(WalletBlockchainClientFactory.class);
        WalletAddressRepository walletAddressRepository = Mockito.mock(WalletAddressRepository.class);
        WalletChainCursorRepository walletChainCursorRepository = Mockito.mock(WalletChainCursorRepository.class);
        WalletDepositTransactionRepository walletDepositTransactionRepository = Mockito.mock(WalletDepositTransactionRepository.class);
        Web3j web3j = Mockito.mock(Web3j.class);
        @SuppressWarnings("unchecked")
        Request<?, EthBlockNumber> blockNumberRequest = Mockito.mock(Request.class);
        @SuppressWarnings("unchecked")
        Request<?, EthBlock> blockRequest = Mockito.mock(Request.class);
        @SuppressWarnings("unchecked")
        Request<?, EthGetTransactionReceipt> nativeReceiptRequest = Mockito.mock(Request.class);
        @SuppressWarnings("unchecked")
        Request<?, EthLog> logRequest = Mockito.mock(Request.class);
        @SuppressWarnings("unchecked")
        Request<?, EthCall> decimalsRequest = Mockito.mock(Request.class);

        EthBlockNumber blockNumberResponse = new EthBlockNumber();
        blockNumberResponse.setResult("0x10");
        EthBlock ethBlock = Mockito.mock(EthBlock.class);
        EthBlock.Block block = Mockito.mock(EthBlock.Block.class);
        EthBlock.TransactionObject erc20Transaction = Mockito.mock(EthBlock.TransactionObject.class);
        EthBlock.TransactionObject nativeTransaction = Mockito.mock(EthBlock.TransactionObject.class);
        EthGetTransactionReceipt nativeReceiptResponse = Mockito.mock(EthGetTransactionReceipt.class);
        TransactionReceipt nativeTransactionReceipt = Mockito.mock(TransactionReceipt.class);
        EthLog logResponse = ethLogResponse(erc20TransferLog(
                "0xbad-erc20",
                badTokenAddress,
                sourceAddress,
                platformAddress,
                1,
                new BigInteger("1000000"),
                16L
        ));

        when(walletBlockchainClientFactory.createEvmClient(URI.create("https://rpc.example.org"))).thenReturn(web3j);
        when(walletChainCursorRepository.findByChain(ChainType.ETH)).thenReturn(new WalletChainCursor(
                1L,
                ChainType.ETH,
                "block",
                "15",
                OffsetDateTime.now()
        ));
        when(walletAddressRepository.findAssignedAddressesByChain(ChainType.ETH)).thenReturn(Set.of(platformAddress));
        when(walletDepositTransactionRepository.findByChainAndBlockRange(ChainType.ETH, 16L, 16L)).thenReturn(List.of());
        doReturn(blockNumberRequest).when(web3j).ethBlockNumber();
        when(blockNumberRequest.send()).thenReturn(blockNumberResponse);
        doReturn(blockRequest).when(web3j).ethGetBlockByNumber(any(), eq(true));
        when(blockRequest.send()).thenReturn(ethBlock);
        when(ethBlock.getBlock()).thenReturn(block);
        when(block.getTransactions()).thenReturn(List.of(
                (EthBlock.TransactionResult<?>) () -> erc20Transaction,
                (EthBlock.TransactionResult<?>) () -> nativeTransaction
        ));

        when(erc20Transaction.getHash()).thenReturn("0xbad-erc20");
        when(erc20Transaction.getTo()).thenReturn(badTokenAddress);
        when(erc20Transaction.getFrom()).thenReturn("0x00000000000000000000000000000000decaf001");
        when(erc20Transaction.getInput()).thenReturn("0xa9059cbb");
        when(erc20Transaction.getValue()).thenReturn(BigInteger.ZERO);
        when(erc20Transaction.getBlockNumber()).thenReturn(BigInteger.valueOf(16));

        when(nativeTransaction.getHash()).thenReturn("0xgood-native");
        when(nativeTransaction.getTo()).thenReturn(platformAddress);
        when(nativeTransaction.getFrom()).thenReturn(sourceAddress);
        when(nativeTransaction.getInput()).thenReturn("0x");
        when(nativeTransaction.getValue()).thenReturn(new BigInteger("1000000000000000000"));
        when(nativeTransaction.getBlockNumber()).thenReturn(BigInteger.valueOf(16));

        doReturn(nativeReceiptRequest).when(web3j).ethGetTransactionReceipt("0xgood-native");
        when(nativeReceiptRequest.send()).thenReturn(nativeReceiptResponse);
        when(nativeReceiptResponse.getTransactionReceipt()).thenReturn(Optional.of(nativeTransactionReceipt));
        when(nativeTransactionReceipt.isStatusOK()).thenReturn(true);
        when(nativeTransactionReceipt.getLogs()).thenReturn(List.of());
        doReturn(logRequest).when(web3j).ethGetLogs(any(EthFilter.class));
        when(logRequest.send()).thenReturn(logResponse);

        doReturn(decimalsRequest).when(web3j).ethCall(any(), eq(DefaultBlockParameterName.LATEST));
        when(decimalsRequest.send()).thenThrow(new IOException("metadata rpc failed"));

        @SuppressWarnings("unchecked")
        Consumer<ObservedDepositTransaction> depositConsumer = Mockito.mock(Consumer.class);
        Web3jChainDepositListener listener = createListener(
                URI.create("https://rpc.example.org"),
                walletBlockchainClientFactory,
                walletAddressRepository,
                walletChainCursorRepository,
                walletDepositTransactionRepository,
                Duration.ofDays(1),
                1,
                "0"
        );
        try {
            listener.start(depositConsumer);

            ArgumentCaptor<ObservedDepositTransaction> observedCaptor = ArgumentCaptor.forClass(ObservedDepositTransaction.class);
            verify(depositConsumer).accept(observedCaptor.capture());
            ObservedDepositTransaction observedDeposit = observedCaptor.getValue();

            org.junit.jupiter.api.Assertions.assertEquals("0xgood-native", observedDeposit.txHash());
            org.junit.jupiter.api.Assertions.assertEquals("ETH", observedDeposit.token());
            verify(walletChainCursorRepository).updateCursor(ChainType.ETH, "16");
            verify(web3j, never()).ethGetTransactionReceipt("0xbad-erc20");
        } finally {
            listener.destroy();
        }
    }

    @Test
    void shouldCapScanSpanAndMarkCatchingUpWhenCursorFarBehind(CapturedOutput output) throws Exception {
        // FX-074-B：cursor 落后 990 块（10 → 1000），maxScanBlocksPerSync=5 限批，
        // 单轮只扫 5 块（11..15），cursor 推进到 15，标记 catchingUp，reconcile 窗口收窄 [11,15]。
        WalletBlockchainClientFactory factory = Mockito.mock(WalletBlockchainClientFactory.class);
        WalletAddressRepository addressRepo = Mockito.mock(WalletAddressRepository.class);
        WalletChainCursorRepository cursorRepo = Mockito.mock(WalletChainCursorRepository.class);
        WalletDepositTransactionRepository depositRepo = Mockito.mock(WalletDepositTransactionRepository.class);
        Web3j web3j = Mockito.mock(Web3j.class);

        @SuppressWarnings("unchecked")
        Request<?, EthBlockNumber> blockNumberRequest = Mockito.mock(Request.class);
        EthBlockNumber blockNumberResponse = new EthBlockNumber();
        blockNumberResponse.setResult("0x3e8"); // 1000
        @SuppressWarnings("unchecked")
        Request<?, EthBlock> blockRequest = Mockito.mock(Request.class);
        EthBlock ethBlock = Mockito.mock(EthBlock.class);
        EthBlock.Block emptyBlock = Mockito.mock(EthBlock.Block.class);

        when(factory.createEvmClient(URI.create("https://rpc.example.org"))).thenReturn(web3j);
        when(cursorRepo.findByChain(ChainType.ETH)).thenReturn(new WalletChainCursor(
                1L, ChainType.ETH, "block", "10", OffsetDateTime.now()));
        doReturn(blockNumberRequest).when(web3j).ethBlockNumber();
        when(blockNumberRequest.send()).thenReturn(blockNumberResponse);
        doReturn(blockRequest).when(web3j).ethGetBlockByNumber(any(), eq(true));
        when(blockRequest.send()).thenReturn(ethBlock);
        when(ethBlock.getBlock()).thenReturn(emptyBlock);
        when(emptyBlock.getTransactions()).thenReturn(List.of());
        when(addressRepo.findAssignedAddressesByChain(ChainType.ETH)).thenReturn(Set.of());
        when(depositRepo.findByChainAndBlockRange(ChainType.ETH, 11L, 15L)).thenReturn(List.of());

        WalletServiceProperties.Chain chain = new WalletServiceProperties.Chain(
                URI.create("https://rpc.example.org"), Duration.ofDays(1), 1, "block", "0");
        chain.setMaxScanBlocksPerSync(5);
        Web3jChainDepositListener listener = new Web3jChainDepositListener(
                ChainType.ETH, chain, factory, addressRepo, cursorRepo, depositRepo);

        @SuppressWarnings("unchecked")
        Consumer<ObservedDepositTransaction> consumer = Mockito.mock(Consumer.class);
        try {
            listener.start(consumer);
            // scanStart=11, scanEnd=min(11+5-1,1000)=15；单轮只扫 5 块，cursor 推进到 15（非 1000）
            verify(cursorRepo).updateCursor(ChainType.ETH, "15");
            org.junit.jupiter.api.Assertions.assertTrue(output.toString().contains("scannedBlocks=5"),
                    "单轮应只扫 5 块（maxScanBlocksPerSync 限批）");
            org.junit.jupiter.api.Assertions.assertTrue(output.toString().contains("catchingUp=true"),
                    "cursor 落后应标记 catchingUp");
            org.junit.jupiter.api.Assertions.assertTrue(output.toString().contains("wallet.listener.catchingUp chain=ETH"),
                    "应输出分批追赶告警");
            // reconcile 窗口收窄到 [11,15]，不查 scanEnd 之外的块（防误判 reversal）
            verify(depositRepo).findByChainAndBlockRange(ChainType.ETH, 11L, 15L);
        } finally {
            listener.destroy();
        }
    }

    private Web3jChainDepositListener createListener(URI rpcUrl,
                                                     WalletBlockchainClientFactory walletBlockchainClientFactory,
                                                     WalletAddressRepository walletAddressRepository,
                                                     WalletChainCursorRepository walletChainCursorRepository,
                                                     WalletDepositTransactionRepository walletDepositTransactionRepository,
                                                     Duration scanInterval,
                                                     int requiredConfirmations,
                                                     String initialCursor) {
        return new Web3jChainDepositListener(
                ChainType.ETH,
                new WalletServiceProperties.Chain(
                        rpcUrl,
                        scanInterval,
                        requiredConfirmations,
                        "block",
                        initialCursor
                ),
                walletBlockchainClientFactory,
                walletAddressRepository,
                walletChainCursorRepository,
                walletDepositTransactionRepository
        );
    }

    private ListAppender<ILoggingEvent> attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(Web3jChainDepositListener.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(Web3jChainDepositListener.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private void assertTraceIdPresent(ListAppender<ILoggingEvent> appender, String messageFragment) {
        ILoggingEvent matchedEvent = appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains(messageFragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到日志事件: " + messageFragment));
        String traceId = matchedEvent.getMDCPropertyMap().get(TraceIdConstants.TRACE_ID_MDC_KEY);
        org.junit.jupiter.api.Assertions.assertNotNull(traceId);
        org.junit.jupiter.api.Assertions.assertFalse(traceId.isBlank());
    }

    private Log erc20TransferLog(String txHash,
                                 String contractAddress,
                                 String fromAddress,
                                 String toAddress,
                                 int logIndex,
                                 BigInteger rawAmount) {
        return erc20TransferLog(txHash, contractAddress, fromAddress, toAddress, logIndex, rawAmount, null);
    }

    private Log erc20TransferLog(String txHash,
                                 String contractAddress,
                                 String fromAddress,
                                 String toAddress,
                                 int logIndex,
                                 BigInteger rawAmount,
                                 Long blockNumber) {
        Log log = new Log();
        log.setTransactionHash(txHash);
        log.setAddress(contractAddress);
        log.setLogIndex(Numeric.toHexStringWithPrefix(BigInteger.valueOf(logIndex)));
        log.setData(Numeric.toHexStringWithPrefixSafe(rawAmount));
        log.setTopics(List.of(
                "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                indexedAddressTopic(fromAddress),
                indexedAddressTopic(toAddress)
        ));
        if (blockNumber != null) {
            log.setBlockNumber(Numeric.toHexStringWithPrefix(BigInteger.valueOf(blockNumber)));
        }
        return log;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private EthLog ethLogResponse(Log... logs) {
        EthLog response = new EthLog();
        List<EthLog.LogResult> results = new ArrayList<>();
        for (Log log : logs) {
            results.add((EthLog.LogResult<Log>) () -> log);
        }
        response.setResult(results);
        return response;
    }

    private String indexedAddressTopic(String address) {
        return "0x000000000000000000000000" + address.substring(2).toLowerCase();
    }
}
