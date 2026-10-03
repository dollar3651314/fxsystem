package com.falconx.wallet.listener;

import com.falconx.domain.enums.ChainType;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.infrastructure.trace.TraceIdSupport;
import com.falconx.wallet.client.TronChainClient;
import com.falconx.wallet.client.WalletBlockchainClientFactory;
import com.falconx.wallet.config.WalletServiceProperties;
import com.falconx.wallet.entity.WalletChainCursor;
import com.falconx.wallet.entity.WalletDepositStatus;
import com.falconx.wallet.entity.WalletDepositTransaction;
import com.falconx.wallet.repository.WalletAddressRepository;
import com.falconx.wallet.repository.WalletChainCursorRepository;
import com.falconx.wallet.repository.WalletDepositTransactionRepository;
import com.falconx.wallet.util.WalletLogSanitizer;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.tron.trident.utils.Base58Check;
import org.tron.trident.utils.Numeric;

/**
 * 基于 java-tron HTTP API 的 TRC20 入金监听器。
 *
 * <p>监听器只解析配置合约的 TRC20 `Transfer` 事件，并把链事实转成统一的
 * {@link ObservedDepositTransaction} 交给 wallet 应用层。地址归属、幂等、状态迁移和事件发布仍由应用层负责。
 */
public class TronApiChainDepositListener implements ChainDepositListener, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TronApiChainDepositListener.class);
    private static final long ZERO = 0L;
    private static final long ONE = 1L;
    private static final int BUSINESS_AMOUNT_SCALE = 8;
    private static final String TRC20_TRANSFER_TOPIC =
            "ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    private final WalletServiceProperties.Chain chainProperties;
    private final WalletBlockchainClientFactory walletBlockchainClientFactory;
    private final WalletAddressRepository walletAddressRepository;
    private final WalletChainCursorRepository walletChainCursorRepository;
    private final WalletDepositTransactionRepository walletDepositTransactionRepository;
    // PROD-OPS-EVIDENCE-01 C4：可选指标注册表，未装配 actuator/registry 时为 null，埋点全程守卫判空，
    // 不影响扫块/检测业务逻辑。tag 仅低基数固定链枚举（tron），不记录地址/txHash/RPC 等敏感值。
    private final MeterRegistry meterRegistry;
    private final String chainTag = ChainType.TRON.name().toLowerCase(Locale.ROOT);

    private TronChainClient tronClient;
    private ScheduledExecutorService pollingExecutor;
    private Consumer<ObservedDepositTransaction> depositConsumer;
    private volatile Long lastProcessedBlockNumber;

    public TronApiChainDepositListener(WalletServiceProperties.Chain chainProperties,
                                       WalletBlockchainClientFactory walletBlockchainClientFactory,
                                       WalletAddressRepository walletAddressRepository,
                                       WalletChainCursorRepository walletChainCursorRepository,
                                       WalletDepositTransactionRepository walletDepositTransactionRepository) {
        this(chainProperties,
                walletBlockchainClientFactory,
                walletAddressRepository,
                walletChainCursorRepository,
                walletDepositTransactionRepository,
                null);
    }

    public TronApiChainDepositListener(WalletServiceProperties.Chain chainProperties,
                                       WalletBlockchainClientFactory walletBlockchainClientFactory,
                                       WalletAddressRepository walletAddressRepository,
                                       WalletChainCursorRepository walletChainCursorRepository,
                                       WalletDepositTransactionRepository walletDepositTransactionRepository,
                                       MeterRegistry meterRegistry) {
        this.chainProperties = chainProperties;
        this.walletBlockchainClientFactory = walletBlockchainClientFactory;
        this.walletAddressRepository = walletAddressRepository;
        this.walletChainCursorRepository = walletChainCursorRepository;
        this.walletDepositTransactionRepository = walletDepositTransactionRepository;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ChainType chainType() {
        return ChainType.TRON;
    }

    @Override
    public void start(Consumer<ObservedDepositTransaction> depositConsumer) {
        this.depositConsumer = depositConsumer;
        if (tronClient == null) {
            tronClient = walletBlockchainClientFactory.createTronClient(chainProperties);
        }
        if (lastProcessedBlockNumber == null) {
            lastProcessedBlockNumber = resolveInitialCursorValue();
        }
        log.info("wallet.listener.started chain={} client=java-tron-http rpcUrl={} requiredConfirmations={} scanInterval={} configuredContracts={}",
                ChainType.TRON,
                maskedRpcUrl(),
                chainProperties.getRequiredConfirmations(),
                chainProperties.getScanInterval(),
                chainProperties.tokenContracts().size());
        synchronizeObservedDeposits("bootstrap");
        if (pollingExecutor == null) {
            pollingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "wallet-tron-head-poller");
                thread.setDaemon(true);
                return thread;
            });
            long intervalMillis = Math.max(1000L, chainProperties.getScanInterval().toMillis());
            pollingExecutor.scheduleWithFixedDelay(
                    () -> synchronizeObservedDeposits("poll"),
                    intervalMillis,
                    intervalMillis,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private void synchronizeObservedDeposits(String trigger) {
        String traceId = TraceIdSupport.newTraceId();
        MDC.put(TraceIdConstants.TRACE_ID_MDC_KEY, traceId);
        SyncObservationStats stats = new SyncObservationStats();
        long scanStartedAtNanos = System.nanoTime();
        try {
            long latestBlockNumber = tronClient.fetchLatestBlockNumber();
            if (isBootstrapBaselineRequired()) {
                persistProcessedCursor(latestBlockNumber);
                log.info("wallet.listener.bootstrap.baselineReady chain={} trigger={} blockNumber={} rpcUrl={}",
                        ChainType.TRON,
                        trigger,
                        latestBlockNumber,
                        maskedRpcUrl());
                return;
            }

            List<WalletServiceProperties.TokenContract> configuredContracts = chainProperties.tokenContracts();
            if (configuredContracts.isEmpty()) {
                log.warn("wallet.listener.trc20.skipped chain={} trigger={} reason=no_configured_contract rpcUrl={}",
                        ChainType.TRON,
                        trigger,
                        maskedRpcUrl());
                return;
            }

            long scanStartBlock = resolveScanStartBlock(latestBlockNumber);
            long trackedWindowEnd = Math.max(latestBlockNumber, lastProcessedBlockNumber);
            Set<String> assignedAddressSnapshot = normalizeAssignedAddresses(
                    walletAddressRepository.findAssignedAddressesByChain(ChainType.TRON)
            );
            List<WalletDepositTransaction> trackedTransactionsInWindow = walletDepositTransactionRepository.findByChainAndBlockRange(
                    ChainType.TRON,
                    scanStartBlock,
                    trackedWindowEnd
            );
            Set<String> observedDepositIdentities = new LinkedHashSet<>();
            for (long blockNumber = scanStartBlock; blockNumber <= latestBlockNumber; blockNumber++) {
                processBlock(
                        blockNumber,
                        latestBlockNumber,
                        configuredContracts,
                        assignedAddressSnapshot,
                        observedDepositIdentities,
                        stats
                );
            }
            reconcileMissingConfirmedTransactions(trackedTransactionsInWindow, observedDepositIdentities, stats);
            persistProcessedCursor(latestBlockNumber);
            log.info("wallet.listener.chainHead.synced chain={} trigger={} blockNumber={} scanStart={} addressCount={} trackedWindowCount={} scannedBlocks={} detectedCount={} reversedCount={} rpcUrl={}",
                    ChainType.TRON,
                    trigger,
                    latestBlockNumber,
                    scanStartBlock,
                    assignedAddressSnapshot.size(),
                    trackedTransactionsInWindow.size(),
                    stats.scannedBlocks(),
                    stats.detectedDeposits(),
                    stats.reversedDeposits(),
                    maskedRpcUrl());
        } catch (IOException | RuntimeException ex) {
            log.warn("wallet.listener.chainHead.syncFailed chain={} trigger={} rpcUrl={} lastProcessedBlock={} scannedBlocks={} detectedCount={} reversedCount={}",
                    ChainType.TRON,
                    trigger,
                    maskedRpcUrl(),
                    lastProcessedBlockNumber,
                    stats.scannedBlocks(),
                    stats.detectedDeposits(),
                    stats.reversedDeposits(),
                    ex);
        } finally {
            recordScanDuration(scanStartedAtNanos);
            MDC.remove(TraceIdConstants.TRACE_ID_MDC_KEY);
        }
    }

    private void processBlock(long blockNumber,
                              long latestBlockNumber,
                              List<WalletServiceProperties.TokenContract> configuredContracts,
                              Set<String> assignedAddressSnapshot,
                              Set<String> observedDepositIdentities,
                              SyncObservationStats stats) throws IOException {
        stats.incrementScannedBlocks();
        for (TronChainClient.TronTransactionInfo transactionInfo : tronClient.fetchTransactionInfosByBlockNumber(blockNumber)) {
            processTransactionInfo(
                    transactionInfo,
                    latestBlockNumber,
                    configuredContracts,
                    assignedAddressSnapshot,
                    observedDepositIdentities,
                    stats
            );
        }
    }

    private void processTransactionInfo(TronChainClient.TronTransactionInfo transactionInfo,
                                        long latestBlockNumber,
                                        List<WalletServiceProperties.TokenContract> configuredContracts,
                                        Set<String> assignedAddressSnapshot,
                                        Set<String> observedDepositIdentities,
                                        SyncObservationStats stats) {
        List<TronChainClient.TronLog> logs = transactionInfo.logs();
        for (int index = 0; index < logs.size(); index++) {
            TronChainClient.TronLog logEntry = logs.get(index);
            if (!isTrc20TransferLog(logEntry)) {
                continue;
            }
            try {
                processSingleTrc20TransferLog(
                        transactionInfo.txHash(),
                        index,
                        transactionInfo.blockNumber(),
                        latestBlockNumber,
                        logEntry,
                        configuredContracts,
                        assignedAddressSnapshot,
                        observedDepositIdentities,
                        stats
                );
            } catch (RuntimeException ex) {
                log.warn("wallet.listener.trc20.logSkipped chain={} txHash={} logIndex={} contractAddress={} reason=invalid_log_payload",
                        ChainType.TRON,
                        transactionInfo.txHash(),
                        index,
                        logEntry.contractAddress(),
                        ex);
            }
        }
    }

    private void processSingleTrc20TransferLog(String txHash,
                                               int logIndex,
                                               long blockNumber,
                                               long latestBlockNumber,
                                               TronChainClient.TronLog logEntry,
                                               List<WalletServiceProperties.TokenContract> configuredContracts,
                                               Set<String> assignedAddressSnapshot,
                                               Set<String> observedDepositIdentities,
                                               SyncObservationStats stats) {
        Optional<TokenContractBinding> tokenBinding = resolveConfiguredToken(logEntry.contractAddress(), configuredContracts);
        if (tokenBinding.isEmpty()) {
            return;
        }
        observedDepositIdentities.add(depositIdentity(txHash, logIndex));

        String transferToRaw20 = extractTopicRaw20(logEntry.topics().get(2));
        if (!assignedAddressSnapshot.contains(transferToRaw20)) {
            return;
        }

        BigInteger rawAmount = new BigInteger(cleanHex(logEntry.data()), 16);
        int confirmations = Math.toIntExact(latestBlockNumber - blockNumber + 1);
        TokenContractBinding token = tokenBinding.get();
        emitObservedDeposit(new ObservedDepositTransaction(
                ChainType.TRON,
                token.symbol(),
                raw20ToBase58(token.contractRaw20()),
                txHash,
                logIndex,
                raw20ToBase58(extractTopicRaw20(logEntry.topics().get(1))),
                raw20ToBase58(transferToRaw20),
                normalizeBusinessAmount(rawAmount, token.decimals()),
                blockNumber,
                confirmations,
                OffsetDateTime.now(ZoneOffset.UTC),
                false
        ), stats);
    }

    private Optional<TokenContractBinding> resolveConfiguredToken(String contractAddress,
                                                                  List<WalletServiceProperties.TokenContract> configuredContracts) {
        Optional<String> observedContractRaw20 = normalizeTronAddressToRaw20(contractAddress);
        if (observedContractRaw20.isEmpty()) {
            return Optional.empty();
        }
        for (WalletServiceProperties.TokenContract contract : configuredContracts) {
            Optional<String> configuredContractRaw20 = normalizeTronAddressToRaw20(contract.contractAddress());
            if (configuredContractRaw20.isPresent()
                    && configuredContractRaw20.get().equals(observedContractRaw20.get())) {
                return Optional.of(new TokenContractBinding(
                        contract.symbol(),
                        observedContractRaw20.get(),
                        contract.decimals()
                ));
            }
        }
        return Optional.empty();
    }

    private void reconcileMissingConfirmedTransactions(List<WalletDepositTransaction> trackedTransactionsInWindow,
                                                       Set<String> observedDepositIdentities,
                                                       SyncObservationStats stats) {
        for (WalletDepositTransaction trackedTransaction : trackedTransactionsInWindow) {
            if (trackedTransaction.status() != WalletDepositStatus.CONFIRMED) {
                continue;
            }
            if (observedDepositIdentities.contains(depositIdentity(trackedTransaction.txHash(), trackedTransaction.logIndex()))) {
                continue;
            }
            depositConsumer.accept(new ObservedDepositTransaction(
                    trackedTransaction.chain(),
                    trackedTransaction.token(),
                    trackedTransaction.tokenContractAddress(),
                    trackedTransaction.txHash(),
                    trackedTransaction.logIndex(),
                    trackedTransaction.fromAddress(),
                    trackedTransaction.toAddress(),
                    trackedTransaction.amount(),
                    trackedTransaction.blockNumber(),
                    0,
                    OffsetDateTime.now(ZoneOffset.UTC),
                    true
            ));
            stats.incrementReversedDeposits();
            log.warn("wallet.listener.deposit.reversedDetected chain={} txHash={} blockNumber={} rpcUrl={}",
                    ChainType.TRON,
                    trackedTransaction.txHash(),
                    trackedTransaction.blockNumber(),
                    maskedRpcUrl());
        }
    }

    private long resolveInitialCursorValue() {
        WalletChainCursor currentCursor = walletChainCursorRepository.findByChain(ChainType.TRON);
        if (currentCursor == null || currentCursor.cursorValue() == null || currentCursor.cursorValue().isBlank()) {
            return parseCursorValue(chainProperties.getInitialCursor());
        }
        return parseCursorValue(currentCursor.cursorValue());
    }

    private long parseCursorValue(String cursorValue) {
        try {
            return Long.parseLong(cursorValue);
        } catch (NumberFormatException ex) {
            log.warn("wallet.listener.cursor.invalid chain={} cursorValue={} rpcUrl={}",
                    ChainType.TRON,
                    cursorValue,
                    maskedRpcUrl(),
                    ex);
            return ZERO;
        }
    }

    private boolean isBootstrapBaselineRequired() {
        return Objects.equals(lastProcessedBlockNumber, parseCursorValue(chainProperties.getInitialCursor()));
    }

    private long resolveScanStartBlock(long latestBlockNumber) {
        long confirmationWindow = Math.max(1, chainProperties.getRequiredConfirmations());
        long referenceHead = Math.min(latestBlockNumber, lastProcessedBlockNumber);
        long rescanStart = referenceHead - confirmationWindow + 2;
        if (rescanStart < ONE) {
            return ONE;
        }
        if (rescanStart > latestBlockNumber) {
            return latestBlockNumber;
        }
        return rescanStart;
    }

    private Set<String> normalizeAssignedAddresses(Set<String> assignedAddresses) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String assignedAddress : assignedAddresses) {
            normalizeTronAddressToRaw20(assignedAddress).ifPresent(normalized::add);
        }
        return normalized;
    }

    private boolean isTrc20TransferLog(TronChainClient.TronLog logEntry) {
        return logEntry != null
                && logEntry.topics() != null
                && logEntry.topics().size() >= 3
                && TRC20_TRANSFER_TOPIC.equals(cleanHex(logEntry.topics().get(0)));
    }

    private Optional<String> normalizeTronAddressToRaw20(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        String trimmed = address.trim();
        String cleanHex = cleanHex(trimmed);
        if (isHex(cleanHex)) {
            String lowered = cleanHex.toLowerCase(Locale.ROOT);
            if (lowered.length() == 42 && lowered.startsWith("41")) {
                return Optional.of(lowered.substring(2));
            }
            if (lowered.length() == 40) {
                return Optional.of(lowered);
            }
        }

        try {
            byte[] decoded = Base58Check.base58ToBytes(trimmed);
            if (decoded.length == 21 && decoded[0] == 0x41) {
                return Optional.of(Numeric.toHexStringNoPrefix(decoded).substring(2).toLowerCase(Locale.ROOT));
            }
        } catch (RuntimeException ignored) {
            // 不是合法 Base58Check 地址时按无效地址处理。
        }
        return Optional.empty();
    }

    private String extractTopicRaw20(String topicValue) {
        String clean = cleanHex(topicValue);
        if (!isHex(clean) || clean.length() < 40) {
            throw new IllegalStateException("Invalid TRON indexed address topic: " + topicValue);
        }
        return clean.substring(clean.length() - 40).toLowerCase(Locale.ROOT);
    }

    private String raw20ToBase58(String raw20Address) {
        byte[] raw20 = Numeric.hexStringToByteArray(raw20Address);
        if (raw20.length != 20) {
            throw new IllegalArgumentException("TRON raw address must be 20 bytes");
        }
        byte[] tronAddress = new byte[21];
        tronAddress[0] = 0x41;
        System.arraycopy(raw20, 0, tronAddress, 1, raw20.length);
        return Base58Check.bytesToBase58(tronAddress);
    }

    private BigDecimal normalizeBusinessAmount(BigInteger rawValue, int decimals) {
        return new BigDecimal(rawValue)
                .divide(BigDecimal.TEN.pow(decimals), BUSINESS_AMOUNT_SCALE, RoundingMode.DOWN);
    }

    private void emitObservedDeposit(ObservedDepositTransaction observedDeposit, SyncObservationStats stats) {
        depositConsumer.accept(observedDeposit);
        stats.incrementDetectedDeposits();
        recordDetectedDeposit();
        log.info("wallet.listener.deposit.detected chain={} txHash={} logIndex={} token={} toAddress={} confirmations={} amount={}",
                ChainType.TRON,
                observedDeposit.txHash(),
                observedDeposit.logIndex(),
                observedDeposit.token(),
                observedDeposit.toAddress(),
                observedDeposit.confirmations(),
                observedDeposit.amount());
    }

    private void persistProcessedCursor(long processedBlockNumber) {
        walletChainCursorRepository.updateCursor(ChainType.TRON, Long.toString(processedBlockNumber));
        lastProcessedBlockNumber = processedBlockNumber;
    }

    private String maskedRpcUrl() {
        return WalletLogSanitizer.maskRpcUrl(chainProperties.getRpcUrl());
    }

    private String depositIdentity(String txHash, int logIndex) {
        return normalizeHash(txHash) + "#" + logIndex;
    }

    private String normalizeHash(String txHash) {
        return txHash == null ? null : txHash.toLowerCase(Locale.ROOT);
    }

    private static String cleanHex(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return trimmed.substring(2);
        }
        return trimmed;
    }

    private static boolean isHex(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean digit = ch >= '0' && ch <= '9';
            boolean lower = ch >= 'a' && ch <= 'f';
            boolean upper = ch >= 'A' && ch <= 'F';
            if (!digit && !lower && !upper) {
                return false;
            }
        }
        return true;
    }

    /**
     * 记录单链单轮扫块耗时。tag 仅低基数固定链枚举（tron）。
     */
    private void recordScanDuration(long startedAtNanos) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.timer("falconx.wallet.chain.scan.duration", "chain", chainTag)
                .record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 每检测到一笔入金 increment。tag 仅低基数固定链枚举（tron）。
     */
    private void recordDetectedDeposit() {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter("falconx.wallet.deposit.detected.total", "chain", chainTag).increment();
    }

    @Override
    public void destroy() {
        if (pollingExecutor != null) {
            pollingExecutor.shutdownNow();
        }
        if (tronClient != null) {
            tronClient.close();
        }
    }

    private record TokenContractBinding(String symbol, String contractRaw20, int decimals) {
    }

    private static final class SyncObservationStats {

        private int scannedBlocks;
        private int detectedDeposits;
        private int reversedDeposits;

        private void incrementScannedBlocks() {
            scannedBlocks++;
        }

        private void incrementDetectedDeposits() {
            detectedDeposits++;
        }

        private void incrementReversedDeposits() {
            reversedDeposits++;
        }

        private int scannedBlocks() {
            return scannedBlocks;
        }

        private int detectedDeposits() {
            return detectedDeposits;
        }

        private int reversedDeposits() {
            return reversedDeposits;
        }
    }
}
