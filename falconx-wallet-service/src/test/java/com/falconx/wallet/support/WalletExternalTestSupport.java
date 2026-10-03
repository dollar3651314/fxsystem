package com.falconx.wallet.support;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;
import org.springframework.boot.test.system.CapturedOutput;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

/**
 * wallet 外部真节点测试支撑。
 *
 * <p>这组辅助方法只服务于显式开启的外部集成测试：
 *
 * <ul>
 *   <li>解析外部 ETH RPC 地址与失败路径地址</li>
 *   <li>从真实区块里动态发现最近的原生币转账样本</li>
 *   <li>统一等待异步链头推进与日志输出</li>
 * </ul>
 */
public final class WalletExternalTestSupport {

    private static final int BUSINESS_AMOUNT_SCALE = 8;
    private static final int NATIVE_TOKEN_DECIMALS = 18;

    private WalletExternalTestSupport() {
    }

    public static URI externalEthRpcUrl() {
        String rpcUrl = System.getenv("FALCONX_WALLET_ETH_RPC_URL");
        if (rpcUrl == null || rpcUrl.isBlank()) {
            throw new IllegalStateException("缺少环境变量 FALCONX_WALLET_ETH_RPC_URL");
        }
        return URI.create(rpcUrl);
    }

    public static URI externalEthInvalidRpcUrl() {
        String override = System.getenv("FALCONX_WALLET_ETH_INVALID_RPC_URL");
        if (override != null && !override.isBlank()) {
            return URI.create(override);
        }
        String base = externalEthRpcUrl().toString();
        int separator = base.lastIndexOf('/');
        if (separator < 0 || separator == base.length() - 1) {
            throw new IllegalStateException("无法从 FALCONX_WALLET_ETH_RPC_URL 推导失败路径地址: " + base);
        }
        return URI.create(base.substring(0, separator + 1) + "falconx-invalid-key");
    }

    public static NativeTransferCandidate findRecentNativeTransfer(Web3j web3j, int lookbackBlocks) throws IOException {
        BigInteger latestBlockNumber = web3j.ethBlockNumber().send().getBlockNumber();
        BigInteger lowerBound = latestBlockNumber.subtract(BigInteger.valueOf(Math.max(1L, lookbackBlocks - 1L)));
        if (lowerBound.compareTo(BigInteger.ONE) < 0) {
            lowerBound = BigInteger.ONE;
        }

        for (BigInteger blockNumber = latestBlockNumber;
             blockNumber.compareTo(lowerBound) >= 0;
             blockNumber = blockNumber.subtract(BigInteger.ONE)) {
            EthBlock.Block block = web3j.ethGetBlockByNumber(new DefaultBlockParameterNumber(blockNumber), true)
                    .send()
                    .getBlock();
            if (block == null) {
                continue;
            }
            Optional<NativeTransferCandidate> candidate = resolveCandidateFromBlock(web3j, latestBlockNumber, block);
            if (candidate.isPresent()) {
                return candidate.get();
            }
        }
        Assertions.fail("未能在最近 " + lookbackBlocks + " 个真实区块中找到可用于自动化验证的原生币转账样本");
        return null;
    }

    public static void waitForCondition(Duration timeout,
                                        String failureMessage,
                                        Supplier<Boolean> condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.get()) {
                return;
            }
            sleepBriefly();
        }
        Assertions.fail(failureMessage);
    }

    public static void waitForLog(CapturedOutput output, String keyword, Duration timeout) {
        waitForCondition(
                timeout,
                "未在超时内观察到日志关键字: " + keyword + System.lineSeparator() + combinedOutput(output),
                () -> combinedOutput(output).contains(keyword)
        );
    }

    public static String combinedOutput(CapturedOutput output) {
        return output.getOut() + output.getErr();
    }

    public static int countOccurrences(String content, String keyword) {
        int occurrences = 0;
        int index = 0;
        while ((index = content.indexOf(keyword, index)) >= 0) {
            occurrences++;
            index += keyword.length();
        }
        return occurrences;
    }

    private static Optional<NativeTransferCandidate> resolveCandidateFromBlock(Web3j web3j,
                                                                               BigInteger latestBlockNumber,
                                                                               EthBlock.Block block)
            throws IOException {
        for (EthBlock.TransactionResult<?> transactionResult : block.getTransactions()) {
            Object payload = transactionResult.get();
            if (!(payload instanceof EthBlock.TransactionObject transaction)) {
                continue;
            }
            if (transaction.getTo() == null
                    || transaction.getTo().isBlank()
                    || transaction.getFrom() == null
                    || transaction.getFrom().isBlank()
                    || (transaction.getInput() != null
                    && !transaction.getInput().isBlank()
                    && !"0x".equalsIgnoreCase(transaction.getInput()))
                    || transaction.getValue() == null
                    || transaction.getValue().signum() <= 0) {
                continue;
            }

            BigDecimal normalizedAmount = normalizeBusinessAmount(transaction.getValue());
            if (normalizedAmount.signum() <= 0) {
                continue;
            }

            EthGetTransactionReceipt receiptResponse = web3j.ethGetTransactionReceipt(transaction.getHash()).send();
            Optional<TransactionReceipt> receipt = receiptResponse.getTransactionReceipt();
            if (receipt.isEmpty() || !receipt.get().isStatusOK()) {
                continue;
            }
            if (receipt.get().getLogs() != null && !receipt.get().getLogs().isEmpty()) {
                continue;
            }

            int confirmations = latestBlockNumber.subtract(transaction.getBlockNumber()).intValueExact() + 1;
            return Optional.of(new NativeTransferCandidate(
                    latestBlockNumber.longValueExact(),
                    transaction.getBlockNumber().longValueExact(),
                    normalizeHash(transaction.getHash()),
                    normalizeAddress(transaction.getFrom()),
                    normalizeAddress(transaction.getTo()),
                    normalizedAmount,
                    confirmations
            ));
        }
        return Optional.empty();
    }

    private static BigDecimal normalizeBusinessAmount(BigInteger rawValue) {
        return new BigDecimal(rawValue)
                .divide(BigDecimal.TEN.pow(NATIVE_TOKEN_DECIMALS), BUSINESS_AMOUNT_SCALE, RoundingMode.DOWN);
    }

    private static String normalizeAddress(String address) {
        return address == null ? null : address.toLowerCase(Locale.ROOT);
    }

    private static String normalizeHash(String txHash) {
        return txHash == null ? null : txHash.toLowerCase(Locale.ROOT);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(200L);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 wallet 外部测试结果时线程被中断", interruptedException);
        }
    }

    /**
     * 最近真实原生币转账样本。
     *
     * @param latestBlockNumber 发现样本时的链头高度
     * @param blockNumber 交易所在区块
     * @param txHash 交易哈希
     * @param fromAddress 来源地址
     * @param toAddress 目标地址
     * @param amount 已按业务口径换算后的金额
     * @param confirmations 发现样本时的确认数
     */
    public record NativeTransferCandidate(
            long latestBlockNumber,
            long blockNumber,
            String txHash,
            String fromAddress,
            String toAddress,
            BigDecimal amount,
            int confirmations
    ) {
    }
}
