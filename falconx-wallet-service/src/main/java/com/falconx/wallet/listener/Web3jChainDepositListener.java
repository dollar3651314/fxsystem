package com.falconx.wallet.listener;

import com.falconx.domain.enums.ChainType;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.infrastructure.trace.TraceIdSupport;
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
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

/**
 * 基于 Web3j 的 EVM 链监听器。
 *
 * <p>该监听器当前落地 Stage 6A 的最小真实链路：
 *
 * <ul>
 *   <li>按 owner 游标扫描 EVM 最新区块范围</li>
 *   <li>识别原生币与 ERC20 `Transfer` 转入平台地址的交易</li>
 *   <li>把链事实转换成统一的 {@link ObservedDepositTransaction}</li>
 *   <li>把扫描推进位置持续回写到 `t_wallet_chain_cursor`</li>
 * </ul>
 *
 * <p>当前实现仍然刻意只保留 wallet owner 自己的链事实解析，
 * 不在本类里混入业务入账或事件发布。
 * ERC20 token metadata 若无法可靠读取，会按 fail-closed 跳过，避免把 raw amount 直接写入 owner 数据。
 */
public class Web3jChainDepositListener implements ChainDepositListener, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(Web3jChainDepositListener.class);
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;
    private static final int BUSINESS_AMOUNT_SCALE = 8;
    private static final int NATIVE_TOKEN_DECIMALS = 18;
    private static final int MAX_SUPPORTED_TOKEN_DECIMALS = 30;
    private static final int ERC20_LOG_ADDRESS_TOPIC_BATCH_SIZE = 100;
    // STAGE-2-DEPOSIT 2026-05-19：Alchemy free tier eth_getLogs 限制 10-block 区间，
    // 超过即 400 RPC error。这里取 10 作为兼容上限；付费节点可改大或下沉到 yaml 配置。
    private static final int ERC20_LOG_BLOCK_WINDOW = 10;
    private static final String ERC20_TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final Function ERC20_DECIMALS_FUNCTION = new Function(
            "decimals",
            List.of(),
            List.of(new TypeReference<Uint8>() {})
    );
    private static final Function ERC20_DECIMALS_FALLBACK_FUNCTION = new Function(
            "decimals",
            List.of(),
            List.of(new TypeReference<Uint256>() {})
    );
    private static final Function ERC20_SYMBOL_FUNCTION = new Function(
            "symbol",
            List.of(),
            List.of(new TypeReference<Utf8String>() {})
    );
    private static final Function ERC20_SYMBOL_FALLBACK_FUNCTION = new Function(
            "symbol",
            List.of(),
            List.of(new TypeReference<Bytes32>() {})
    );

    private final ChainType chainType;
    private final WalletServiceProperties.Chain chainProperties;
    private final WalletBlockchainClientFactory walletBlockchainClientFactory;
    private final WalletAddressRepository walletAddressRepository;
    private final WalletChainCursorRepository walletChainCursorRepository;
    private final WalletDepositTransactionRepository walletDepositTransactionRepository;
    // PROD-OPS-EVIDENCE-01 C4：可选指标注册表，未装配 actuator/registry 时为 null，埋点全程守卫判空，
    // 不影响扫块/检测业务逻辑。tag 仅低基数固定链枚举，不记录地址/txHash/RPC 等敏感值。
    private final MeterRegistry meterRegistry;
    private final String chainTag;

    private Web3j web3j;
    private ScheduledExecutorService pollingExecutor;
    private Consumer<ObservedDepositTransaction> depositConsumer;
    private volatile BigInteger lastProcessedBlockNumber;

    public Web3jChainDepositListener(ChainType chainType,
                                     WalletServiceProperties.Chain chainProperties,
                                     WalletBlockchainClientFactory walletBlockchainClientFactory,
                                     WalletAddressRepository walletAddressRepository,
                                     WalletChainCursorRepository walletChainCursorRepository,
                                     WalletDepositTransactionRepository walletDepositTransactionRepository) {
        this(chainType,
                chainProperties,
                walletBlockchainClientFactory,
                walletAddressRepository,
                walletChainCursorRepository,
                walletDepositTransactionRepository,
                null);
    }

    public Web3jChainDepositListener(ChainType chainType,
                                     WalletServiceProperties.Chain chainProperties,
                                     WalletBlockchainClientFactory walletBlockchainClientFactory,
                                     WalletAddressRepository walletAddressRepository,
                                     WalletChainCursorRepository walletChainCursorRepository,
                                     WalletDepositTransactionRepository walletDepositTransactionRepository,
                                     MeterRegistry meterRegistry) {
        this.chainType = chainType;
        this.chainProperties = chainProperties;
        this.walletBlockchainClientFactory = walletBlockchainClientFactory;
        this.walletAddressRepository = walletAddressRepository;
        this.walletChainCursorRepository = walletChainCursorRepository;
        this.walletDepositTransactionRepository = walletDepositTransactionRepository;
        this.meterRegistry = meterRegistry;
        this.chainTag = chainType.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public ChainType chainType() {
        return chainType;
    }

    @Override
    public void start(Consumer<ObservedDepositTransaction> depositConsumer) {
        this.depositConsumer = depositConsumer;
        if (web3j == null) {
            web3j = walletBlockchainClientFactory.createEvmClient(chainProperties.getRpcUrl());
        }
        if (lastProcessedBlockNumber == null) {
            lastProcessedBlockNumber = resolveInitialCursorValue();
        }
        log.info("wallet.listener.started chain={} client=web3j rpcUrl={} requiredConfirmations={} scanInterval={}",
                chainType,
                maskedRpcUrl(),
                chainProperties.getRequiredConfirmations(),
                chainProperties.getScanInterval());
        // 启动后立即做一次同步：
        // - 若 owner 游标仍是初始值，则仅把基线对齐到最新链头，避免首次启动误扫全历史区块
        // - 若 owner 游标已经存在真实扫描进度，则从最近确认窗口继续扫块并回调应用层
        synchronizeObservedDeposits("bootstrap");
        if (pollingExecutor == null) {
            pollingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "wallet-" + chainType.name().toLowerCase() + "-head-poller");
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
            BigInteger latestBlockNumber = fetchLatestBlockNumber();
            if (latestBlockNumber == null) {
                log.warn("wallet.listener.chainHead.missing chain={} trigger={} rpcUrl={}",
                        chainType,
                        trigger,
                        maskedRpcUrl());
                return;
            }

            if (isBootstrapBaselineRequired()) {
                persistProcessedCursor(latestBlockNumber);
                log.info("wallet.listener.bootstrap.baselineReady chain={} trigger={} blockNumber={} rpcUrl={}",
                        chainType,
                        trigger,
                        latestBlockNumber,
                        maskedRpcUrl());
                return;
            }

            BigInteger scanStartBlock = resolveScanStartBlock(latestBlockNumber);
            // 2026-05-27 FX-074-B：单轮最多扫 maxScanBlocksPerSync 个块。cursor 落后过多时分批追赶，
            // 避免一次性几千次 RPC 打爆 Alchemy 429。本轮扫到 scanEndBlock 即推进 cursor，
            // 下一轮 poll 从 scanEndBlock 继续，逐步追平链头。
            BigInteger scanEndBlock = resolveScanEndBlock(scanStartBlock, latestBlockNumber);
            boolean catchingUp = scanEndBlock.compareTo(latestBlockNumber) < 0;
            // 关键：reconcile 窗口必须与本轮实际扫描范围 [scanStart, scanEnd] 一致。
            // 否则限批时 scanEnd 之外的 CONFIRMED 交易本轮没被扫到（不在 observedDepositIdentities），
            // 会被误判为 reversal → 错误回滚入账（资金安全）。
            Set<String> assignedAddressSnapshot = walletAddressRepository.findAssignedAddressesByChain(chainType);
            List<WalletDepositTransaction> trackedTransactionsInWindow = walletDepositTransactionRepository.findByChainAndBlockRange(
                    chainType,
                    scanStartBlock.longValueExact(),
                    scanEndBlock.longValueExact()
            );
            Set<String> observedDepositIdentities = new LinkedHashSet<>();
            Map<String, Optional<Erc20TokenMetadata>> tokenMetadataCache = new HashMap<>();
            for (BigInteger blockNumber = scanStartBlock; blockNumber.compareTo(scanEndBlock) <= 0; blockNumber = blockNumber.add(ONE)) {
                processBlock(
                        blockNumber,
                        latestBlockNumber,
                        assignedAddressSnapshot,
                        observedDepositIdentities,
                        stats
                );
            }
            processErc20TransferLogs(
                    scanStartBlock,
                    scanEndBlock,
                    assignedAddressSnapshot,
                    observedDepositIdentities,
                    tokenMetadataCache,
                    stats
            );
            reconcileMissingConfirmedTransactions(trackedTransactionsInWindow, observedDepositIdentities, stats);
            persistProcessedCursor(scanEndBlock);
            log.info("wallet.listener.chainHead.synced chain={} trigger={} chainHead={} scanStart={} scanEnd={} catchingUp={} addressCount={} trackedWindowCount={} scannedBlocks={} detectedCount={} reversedCount={} rpcUrl={}",
                    chainType,
                    trigger,
                    latestBlockNumber,
                    scanStartBlock,
                    scanEndBlock,
                    catchingUp,
                    assignedAddressSnapshot.size(),
                    trackedTransactionsInWindow.size(),
                    stats.scannedBlocks(),
                    stats.detectedDeposits(),
                    stats.reversedDeposits(),
                    maskedRpcUrl());
            if (catchingUp) {
                log.warn("wallet.listener.catchingUp chain={} scanEnd={} chainHead={} remainingBlocks={} — cursor 落后，分批追赶中",
                        chainType, scanEndBlock, latestBlockNumber, latestBlockNumber.subtract(scanEndBlock));
            }
        } catch (IOException | RuntimeException ex) {
            // 区块抓取、回执读取或下游应用层处理失败时，不推进当前区块游标。
            // 下一轮轮询会从最后一个成功区块继续重试，依赖应用层 `(chain, txHash)` 幂等避免重复副作用。
            log.warn("wallet.listener.chainHead.syncFailed chain={} trigger={} rpcUrl={} lastProcessedBlock={} scannedBlocks={} detectedCount={} reversedCount={}",
                    chainType,
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

    private BigInteger fetchLatestBlockNumber() throws IOException {
        return web3j.ethBlockNumber().send().getBlockNumber();
    }

    private BigInteger resolveInitialCursorValue() {
        WalletChainCursor currentCursor = walletChainCursorRepository.findByChain(chainType);
        if (currentCursor == null || currentCursor.cursorValue() == null || currentCursor.cursorValue().isBlank()) {
            return parseCursorValue(chainProperties.getInitialCursor());
        }
        return parseCursorValue(currentCursor.cursorValue());
    }

    private BigInteger parseCursorValue(String cursorValue) {
        try {
            return new BigInteger(cursorValue);
        } catch (NumberFormatException ex) {
            log.warn("wallet.listener.cursor.invalid chain={} cursorValue={} rpcUrl={}",
                    chainType,
                    cursorValue,
                    maskedRpcUrl(),
                    ex);
            return ZERO;
        }
    }

    private boolean isBootstrapBaselineRequired() {
        return Objects.equals(lastProcessedBlockNumber, parseCursorValue(chainProperties.getInitialCursor()));
    }

    private BigInteger resolveScanStartBlock(BigInteger latestBlockNumber) {
        // 每轮都回扫一个确认窗口，确保未最终确认的原始交易能随着链头推进重新进入应用层，
        // 从而把 `DETECTED/CONFIRMING` 平稳推进到 `CONFIRMED`。
        // 若链头保持不变或回退，也继续按当前 canonical window 回扫，
        // 这样已确认交易在链重组后从窗口中消失时，listener 才能识别 reversal 候选。
        BigInteger confirmationWindow = BigInteger.valueOf(Math.max(1, chainProperties.getRequiredConfirmations()));
        BigInteger referenceHead = latestBlockNumber.min(lastProcessedBlockNumber);
        BigInteger rescanStart = referenceHead.subtract(confirmationWindow).add(BigInteger.TWO);
        if (rescanStart.compareTo(ONE) < 0) {
            return ONE;
        }
        if (rescanStart.compareTo(latestBlockNumber) > 0) {
            return latestBlockNumber;
        }
        return rescanStart;
    }

    /**
     * 2026-05-27 FX-074-B：解析本轮扫描终点，限制单轮最多扫 maxScanBlocksPerSync 个块。
     *
     * <p>cursor 落后过多时分批追赶：本轮只扫 [scanStart, scanStart+maxSpan-1]，
     * 不超过链头 latestBlockNumber。maxSpan ≤ 0 表示不限批（兼容旧行为，扫到链头）。
     */
    private BigInteger resolveScanEndBlock(BigInteger scanStartBlock, BigInteger latestBlockNumber) {
        int maxSpan = chainProperties.getMaxScanBlocksPerSync();
        if (maxSpan <= 0) {
            return latestBlockNumber;
        }
        BigInteger cappedEnd = scanStartBlock.add(BigInteger.valueOf(maxSpan)).subtract(ONE);
        return cappedEnd.min(latestBlockNumber);
    }

    private void processBlock(BigInteger blockNumber,
                              BigInteger latestBlockNumber,
                              Set<String> assignedAddressSnapshot,
                              Set<String> observedDepositIdentities,
                              SyncObservationStats stats) throws IOException {
        stats.incrementScannedBlocks();
        EthBlock.Block block = web3j.ethGetBlockByNumber(new DefaultBlockParameterNumber(blockNumber), true)
                .send()
                .getBlock();
        if (block == null) {
            throw new IOException("Missing block payload for " + blockNumber);
        }
        for (EthBlock.TransactionResult<?> transactionResult : block.getTransactions()) {
            Object payload = transactionResult.get();
            if (payload instanceof EthBlock.TransactionObject transactionObject) {
                processTransaction(
                        transactionObject,
                        latestBlockNumber,
                        assignedAddressSnapshot,
                        observedDepositIdentities,
                        stats
                );
            }
        }
    }

    private void processTransaction(EthBlock.TransactionObject transactionObject,
                                    BigInteger latestBlockNumber,
                                    Set<String> assignedAddressSnapshot,
                                    Set<String> observedDepositIdentities,
                                    SyncObservationStats stats) throws IOException {
        String txHash = transactionObject.getHash();
        if (txHash == null || txHash.isBlank()) {
            return;
        }

        String toAddress = transactionObject.getTo();
        boolean nativeTransferObserved = transactionObject.getValue() != null && transactionObject.getValue().signum() > 0;
        if (nativeTransferObserved) {
            observedDepositIdentities.add(depositIdentity(txHash, 0));
        }

        boolean inspectNativeDeposit = toAddress != null
                && !toAddress.isBlank()
                && nativeTransferObserved
                && assignedAddressSnapshot.contains(normalizeAddress(toAddress));
        if (!inspectNativeDeposit) {
            return;
        }

        EthGetTransactionReceipt receiptResponse = web3j.ethGetTransactionReceipt(txHash).send();
        Optional<TransactionReceipt> receipt = receiptResponse.getTransactionReceipt();
        if (receipt.isEmpty() || !receipt.get().isStatusOK()) {
            log.debug("wallet.listener.deposit.ignored chain={} txHash={} reason=receipt_not_ready_or_failed",
                    chainType,
                    txHash);
            return;
        }

        BigInteger transactionBlockNumber = transactionObject.getBlockNumber();
        int confirmations = latestBlockNumber.subtract(transactionBlockNumber).intValueExact() + 1;
        if (inspectNativeDeposit) {
            emitObservedDeposit(new ObservedDepositTransaction(
                    chainType,
                    resolveNativeTokenSymbol(),
                    null,
                    txHash,
                    0,
                    transactionObject.getFrom(),
                    toAddress,
                    normalizeBusinessAmount(transactionObject.getValue(), NATIVE_TOKEN_DECIMALS),
                    transactionBlockNumber.longValueExact(),
                    confirmations,
                    OffsetDateTime.now(ZoneOffset.UTC),
                    false
            ), stats);
        }
    }

    private void processErc20TransferLogs(BigInteger scanStartBlock,
                                          BigInteger latestBlockNumber,
                                          Set<String> assignedAddressSnapshot,
                                          Set<String> observedDepositIdentities,
                                          Map<String, Optional<Erc20TokenMetadata>> tokenMetadataCache,
                                          SyncObservationStats stats) throws IOException {
        List<String> toAddressTopics = assignedAddressSnapshot.stream()
                .map(this::toIndexedAddressTopic)
                .filter(Objects::nonNull)
                .toList();
        if (toAddressTopics.isEmpty()) {
            return;
        }

        List<String> configuredContractAddresses = chainProperties.tokenContracts().stream()
                .map(WalletServiceProperties.TokenContract::contractAddress)
                .toList();
        for (List<String> topicBatch : partition(toAddressTopics, ERC20_LOG_ADDRESS_TOPIC_BATCH_SIZE)) {
            BigInteger windowStart = scanStartBlock;
            BigInteger windowSize = BigInteger.valueOf(ERC20_LOG_BLOCK_WINDOW);
            while (windowStart.compareTo(latestBlockNumber) <= 0) {
                BigInteger windowEnd = windowStart.add(windowSize).subtract(ONE).min(latestBlockNumber);
                EthFilter filter = new EthFilter(
                        new DefaultBlockParameterNumber(windowStart),
                        new DefaultBlockParameterNumber(windowEnd),
                        configuredContractAddresses.isEmpty() ? null : configuredContractAddresses
                );
                filter.addSingleTopic(ERC20_TRANSFER_TOPIC);
                filter.addNullTopic();
                filter.addOptionalTopics(topicBatch.toArray(String[]::new));
                EthLog response = web3j.ethGetLogs(filter).send();
                List<EthLog.LogResult> logs = response.getLogs();
                if (logs != null && !logs.isEmpty()) {
                    for (EthLog.LogResult<?> logResult : logs) {
                        Object payload = logResult.get();
                        if (payload instanceof Log logEntry && isErc20TransferLog(logEntry)) {
                            try {
                                processSingleErc20TransferLog(
                                        logEntry,
                                        latestBlockNumber,
                                        assignedAddressSnapshot,
                                        observedDepositIdentities,
                                        tokenMetadataCache,
                                        stats
                                );
                            } catch (RuntimeException ex) {
                                log.warn("wallet.listener.erc20.logSkipped chain={} txHash={} contractAddress={} reason=invalid_log_payload",
                                        chainType,
                                        logEntry.getTransactionHash(),
                                        logEntry.getAddress(),
                                        ex);
                            }
                        }
                    }
                }
                windowStart = windowEnd.add(ONE);
            }
        }
    }

    private void processSingleErc20TransferLog(Log logEntry,
                                               BigInteger latestBlockNumber,
                                               Set<String> assignedAddressSnapshot,
                                               Set<String> observedDepositIdentities,
                                               Map<String, Optional<Erc20TokenMetadata>> tokenMetadataCache,
                                               SyncObservationStats stats) throws IOException {
        String txHash = logEntry.getTransactionHash();
        if (txHash == null || txHash.isBlank()) {
            throw new IllegalStateException("Missing transaction hash for ERC20 transfer log");
        }
        BigInteger blockNumber = logEntry.getBlockNumber();
        if (blockNumber == null) {
            throw new IllegalStateException("Missing block number for ERC20 transfer log");
        }
        int logIndex = resolveLogIndex(logEntry);
        observedDepositIdentities.add(depositIdentity(txHash, logIndex));

        String transferToAddress = extractIndexedAddress(logEntry.getTopics().get(2));
        if (!assignedAddressSnapshot.contains(normalizeAddress(transferToAddress))) {
            return;
        }

        String contractAddress = logEntry.getAddress();
        Optional<WalletServiceProperties.TokenContract> configuredToken = chainProperties.findTokenContract(contractAddress);
        Optional<Erc20TokenMetadata> tokenMetadata = configuredToken
                .map(token -> new Erc20TokenMetadata(token.symbol(), token.decimals()));
        if (tokenMetadata.isEmpty()) {
            tokenMetadata = resolveErc20TokenMetadata(contractAddress, tokenMetadataCache);
        }
        if (tokenMetadata.isEmpty()) {
            log.warn("wallet.listener.erc20.skipped chain={} txHash={} logIndex={} contractAddress={} reason=metadata_unavailable",
                    chainType,
                    txHash,
                    logIndex,
                    contractAddress);
            return;
        }

        BigInteger rawAmount = Numeric.toBigInt(logEntry.getData());
        Erc20TokenMetadata metadata = tokenMetadata.get();
        int confirmations = latestBlockNumber.subtract(blockNumber).intValueExact() + 1;
        emitObservedDeposit(new ObservedDepositTransaction(
                chainType,
                metadata.symbol(),
                contractAddress,
                txHash,
                logIndex,
                extractIndexedAddress(logEntry.getTopics().get(1)),
                transferToAddress,
                normalizeBusinessAmount(rawAmount, metadata.decimals()),
                blockNumber.longValueExact(),
                confirmations,
                OffsetDateTime.now(ZoneOffset.UTC),
                false
        ), stats);
    }

    private void reconcileMissingConfirmedTransactions(List<WalletDepositTransaction> trackedTransactionsInWindow,
                                                       Set<String> observedDepositIdentities,
                                                       SyncObservationStats stats) {
        // 当前对账只把“已 CONFIRMED 且在当前 canonical block window 中彻底消失”的记录视为 reversal 候选。
        // 未确认阶段的交易即使暂时消失，也保持既有状态等待后续链事实，避免 listener 提前做业务回滚判断。
        for (WalletDepositTransaction trackedTransaction : trackedTransactionsInWindow) {
            if (trackedTransaction.status() != WalletDepositStatus.CONFIRMED) {
                continue;
            }
            if (observedDepositIdentities.contains(depositIdentity(trackedTransaction.txHash(), trackedTransaction.logIndex()))) {
                continue;
            }
            ObservedDepositTransaction reversedObservation = new ObservedDepositTransaction(
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
            );
            depositConsumer.accept(reversedObservation);
            stats.incrementReversedDeposits();
            log.warn("wallet.listener.deposit.reversedDetected chain={} txHash={} blockNumber={} rpcUrl={}",
                    chainType,
                    trackedTransaction.txHash(),
                    trackedTransaction.blockNumber(),
                    maskedRpcUrl());
        }
    }

    private String maskedRpcUrl() {
        return WalletLogSanitizer.maskRpcUrl(chainProperties.getRpcUrl());
    }

    private void emitObservedDeposit(ObservedDepositTransaction observedDeposit, SyncObservationStats stats) {
        // 监听层只负责识别链事实并做最小字段转换。
        // 去重、状态迁移、落库与低频事件发布仍由应用层串行编排，避免 listener 再维护第二套幂等逻辑。
        depositConsumer.accept(observedDeposit);
        stats.incrementDetectedDeposits();
        recordDetectedDeposit();
        log.info("wallet.listener.deposit.detected chain={} txHash={} logIndex={} token={} toAddress={} confirmations={} amount={}",
                chainType,
                observedDeposit.txHash(),
                observedDeposit.logIndex(),
                observedDeposit.token(),
                observedDeposit.toAddress(),
                observedDeposit.confirmations(),
                observedDeposit.amount());
    }

    private Optional<Erc20TokenMetadata> resolveErc20TokenMetadata(String contractAddress,
                                                                   Map<String, Optional<Erc20TokenMetadata>> tokenMetadataCache)
            throws IOException {
        String normalizedContractAddress = normalizeAddress(contractAddress);
        Optional<Erc20TokenMetadata> cached = tokenMetadataCache.get(normalizedContractAddress);
        if (cached != null) {
            return cached;
        }
        Optional<Erc20TokenMetadata> resolved;
        try {
            resolved = fetchErc20TokenMetadata(contractAddress);
        } catch (IOException | RuntimeException ex) {
            log.warn("wallet.listener.erc20.metadataFetchFailed chain={} contractAddress={}",
                    chainType,
                    contractAddress,
                    ex);
            resolved = Optional.empty();
        }
        tokenMetadataCache.put(normalizedContractAddress, resolved);
        return resolved;
    }

    private Optional<Erc20TokenMetadata> fetchErc20TokenMetadata(String contractAddress) throws IOException {
        Optional<Integer> decimals = callErc20Decimals(contractAddress);
        Optional<String> symbol = callErc20Symbol(contractAddress);
        if (decimals.isEmpty() || symbol.isEmpty()) {
            return Optional.empty();
        }
        int resolvedDecimals = decimals.get();
        if (resolvedDecimals < 0 || resolvedDecimals > MAX_SUPPORTED_TOKEN_DECIMALS) {
            log.warn("wallet.listener.erc20.metadata.invalid chain={} contractAddress={} decimals={} maxSupported={}",
                    chainType,
                    contractAddress,
                    resolvedDecimals,
                    MAX_SUPPORTED_TOKEN_DECIMALS);
            return Optional.empty();
        }
        return Optional.of(new Erc20TokenMetadata(symbol.get(), resolvedDecimals));
    }

    private Optional<Integer> callErc20Decimals(String contractAddress) throws IOException {
        Optional<String> rawValue = ethCall(contractAddress, ERC20_DECIMALS_FUNCTION);
        if (rawValue.isEmpty()) {
            rawValue = ethCall(contractAddress, ERC20_DECIMALS_FALLBACK_FUNCTION);
        }
        if (rawValue.isEmpty()) {
            return Optional.empty();
        }

        List<org.web3j.abi.datatypes.Type> decoded = FunctionReturnDecoder.decode(
                rawValue.get(),
                ERC20_DECIMALS_FUNCTION.getOutputParameters()
        );
        if (!decoded.isEmpty() && decoded.get(0) instanceof Uint8 decimals) {
            return Optional.of(decimals.getValue().intValueExact());
        }

        List<org.web3j.abi.datatypes.Type> fallbackDecoded = FunctionReturnDecoder.decode(
                rawValue.get(),
                ERC20_DECIMALS_FALLBACK_FUNCTION.getOutputParameters()
        );
        if (!fallbackDecoded.isEmpty() && fallbackDecoded.get(0) instanceof Uint256 decimals) {
            return Optional.of(decimals.getValue().intValueExact());
        }
        return Optional.empty();
    }

    private Optional<String> callErc20Symbol(String contractAddress) throws IOException {
        Optional<String> rawValue = ethCall(contractAddress, ERC20_SYMBOL_FUNCTION);
        if (rawValue.isPresent()) {
            List<org.web3j.abi.datatypes.Type> decoded = FunctionReturnDecoder.decode(
                    rawValue.get(),
                    ERC20_SYMBOL_FUNCTION.getOutputParameters()
            );
            if (!decoded.isEmpty() && decoded.get(0) instanceof Utf8String symbol) {
                String normalized = normalizeTokenSymbol(symbol.getValue());
                if (normalized != null) {
                    return Optional.of(normalized);
                }
            }
        }

        Optional<String> fallbackValue = rawValue.isPresent() ? rawValue : ethCall(contractAddress, ERC20_SYMBOL_FALLBACK_FUNCTION);
        if (fallbackValue.isEmpty()) {
            return Optional.empty();
        }

        List<org.web3j.abi.datatypes.Type> decoded = FunctionReturnDecoder.decode(
                fallbackValue.get(),
                ERC20_SYMBOL_FALLBACK_FUNCTION.getOutputParameters()
        );
        if (!decoded.isEmpty() && decoded.get(0) instanceof Bytes32 bytes32) {
            String normalized = normalizeTokenSymbol(bytes32ToString(bytes32.getValue()));
            if (normalized != null) {
                return Optional.of(normalized);
            }
        }
        return Optional.empty();
    }

    private Optional<String> ethCall(String contractAddress, Function function) throws IOException {
        EthCall response = web3j.ethCall(
                        Transaction.createEthCallTransaction(null, contractAddress, FunctionEncoder.encode(function)),
                        DefaultBlockParameterName.LATEST
                )
                .send();
        String value = response.getValue();
        if (value == null || value.isBlank() || "0x".equalsIgnoreCase(value)) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private BigDecimal normalizeBusinessAmount(BigInteger rawValue, int decimals) {
        // 链监听阶段只允许保留 raw amount。
        // 一旦进入 owner 持久化和事件 payload，必须显式换算成业务金额，并统一保留 8 位小数。
        return new BigDecimal(rawValue)
                .divide(BigDecimal.TEN.pow(decimals), BUSINESS_AMOUNT_SCALE, RoundingMode.DOWN);
    }

    private String resolveNativeTokenSymbol() {
        return switch (chainType) {
            case ETH -> "ETH";
            case BSC -> "BNB";
            default -> chainType.name();
        };
    }

    private String normalizeAddress(String address) {
        return address == null ? null : address.toLowerCase(Locale.ROOT);
    }

    private String normalizeHash(String txHash) {
        return txHash == null ? null : txHash.toLowerCase(Locale.ROOT);
    }

    private String normalizeTokenSymbol(String symbol) {
        if (symbol == null) {
            return null;
        }
        String trimmed = symbol.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private String depositIdentity(String txHash, int logIndex) {
        return normalizeHash(txHash) + "#" + logIndex;
    }

    private boolean isErc20TransferLog(Log logEntry) {
        return logEntry != null
                && !logEntry.isRemoved()
                && logEntry.getTopics() != null
                && logEntry.getTopics().size() >= 3
                && ERC20_TRANSFER_TOPIC.equalsIgnoreCase(logEntry.getTopics().get(0));
    }

    private int resolveLogIndex(Log logEntry) {
        BigInteger logIndex = logEntry.getLogIndex();
        if (logIndex == null) {
            throw new IllegalStateException("Missing log index for ERC20 transfer log");
        }
        return logIndex.intValueExact();
    }

    private String extractIndexedAddress(String topicValue) {
        if (topicValue == null || topicValue.length() < 42) {
            throw new IllegalStateException("Invalid indexed address topic: " + topicValue);
        }
        return normalizeAddress("0x" + topicValue.substring(topicValue.length() - 40));
    }

    private String toIndexedAddressTopic(String address) {
        String normalizedAddress = normalizeAddress(address);
        if (normalizedAddress == null || !normalizedAddress.startsWith("0x") || normalizedAddress.length() != 42) {
            log.warn("wallet.listener.erc20.addressTopicSkipped chain={} reason=invalid_address address={}",
                    chainType,
                    address);
            return null;
        }
        return "0x000000000000000000000000" + normalizedAddress.substring(2);
    }

    private <T> List<List<T>> partition(List<T> values, int batchSize) {
        if (values.isEmpty()) {
            return List.of();
        }
        List<List<T>> partitions = new ArrayList<>();
        for (int index = 0; index < values.size(); index += batchSize) {
            partitions.add(values.subList(index, Math.min(index + batchSize, values.size())));
        }
        return partitions;
    }

    private String bytes32ToString(byte[] rawBytes) {
        int contentLength = 0;
        while (contentLength < rawBytes.length && rawBytes[contentLength] != 0) {
            contentLength++;
        }
        return new String(rawBytes, 0, contentLength, StandardCharsets.UTF_8);
    }

    private void persistProcessedCursor(BigInteger processedBlockNumber) {
        walletChainCursorRepository.updateCursor(chainType, processedBlockNumber.toString());
        lastProcessedBlockNumber = processedBlockNumber;
    }

    /**
     * 记录单链单轮扫块耗时。tag 仅低基数固定链枚举（eth/bsc）。
     */
    private void recordScanDuration(long startedAtNanos) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.timer("falconx.wallet.chain.scan.duration", "chain", chainTag)
                .record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 每检测到一笔入金 increment。tag 仅低基数固定链枚举（eth/bsc）。
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
        if (web3j != null) {
            web3j.shutdown();
        }
    }

    private record Erc20TokenMetadata(String symbol, int decimals) {
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
