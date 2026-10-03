package com.falconx.wallet.config;

import com.falconx.domain.enums.ChainType;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * wallet-service 配置属性。
 *
 * <p>该配置类用于冻结 Stage 2B 钱包事件底座的最小配置结构，
 * 让地址分配、链监听、确认推进与 Kafka 发布都从统一配置入口读取参数。
 * 当前阶段已经切换为真实链 SDK 驱动的监听器骨架，后续只允许继续补轮询与解析逻辑，
 * 不应再改动这里的字段语义。
 */
@ConfigurationProperties(prefix = "falconx.wallet")
public class WalletServiceProperties {

    private final Allocation allocation = new Allocation();
    private final Derivation derivation = new Derivation();
    private final Kafka kafka = new Kafka();
    private final Chains chains = new Chains();
    private final InternalApi internalApi = new InternalApi();
    private final Kms kms = new Kms();
    private final Withdraw withdraw = new Withdraw();

    public Allocation getAllocation() {
        return allocation;
    }

    public Derivation getDerivation() {
        return derivation;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public Chains getChains() {
        return chains;
    }

    public InternalApi getInternalApi() {
        return internalApi;
    }

    public Kms getKms() {
        return kms;
    }

    public Withdraw getWithdraw() {
        return withdraw;
    }

    /**
     * STAGE-7-WITHDRAW Phase 3：出金链路配置（与入金扫块链配置 {@link Chains} 隔离）。
     *
     * <p>出金广播 / 确认调度器使用 {@code withdraw.eth.rpc-url} 接 Alchemy 测试网，
     * 不与入金 {@code chains.eth.rpc-url}（默认 localhost）共用，避免误触发入金扫真链。
     */
    public static class Withdraw {
        private final EthWithdraw eth = new EthWithdraw();

        public EthWithdraw getEth() {
            return eth;
        }
    }

    public static class EthWithdraw {
        /** Sepolia / Hoodi / mainnet 节点 RPC（http / https / ws / wss）。 */
        private URI rpcUrl = URI.create("http://localhost:8545");
        /** EVM chain id（Sepolia=11155111 / Hoodi=560048 / mainnet=1）。 */
        private long chainId = 11155111L;
        /** ERC20 USDT 合约地址，由 env 注入。 */
        private String usdtContract;
        /** USDT decimals。 */
        private int usdtDecimals = 6;
        /** ERC20 USDC 合约地址，由 env 注入。 */
        private String usdcContract;
        /** USDC decimals。 */
        private int usdcDecimals = 6;
        /** transfer 调用建议 gas limit。 */
        private long gasLimit = 100_000L;
        /** gas price 上限（gwei），防恶意 RPC 返回离谱 gas 估算。 */
        private long gasPriceMaxGwei = 100L;
        /** 链上确认数阈值，按 Kafka 事件规范 §12.11 = 12。 */
        private int minConfirmations = 12;
        /** 确认调度器扫描周期。 */
        private Duration confirmationSchedulerInterval = Duration.ofSeconds(30);

        public URI getRpcUrl() { return rpcUrl; }
        public void setRpcUrl(URI rpcUrl) { this.rpcUrl = rpcUrl; }
        public long getChainId() { return chainId; }
        public void setChainId(long chainId) { this.chainId = chainId; }
        public String getUsdtContract() { return usdtContract; }
        public void setUsdtContract(String usdtContract) { this.usdtContract = usdtContract; }
        public int getUsdtDecimals() { return usdtDecimals; }
        public void setUsdtDecimals(int usdtDecimals) { this.usdtDecimals = usdtDecimals; }
        public String getUsdcContract() { return usdcContract; }
        public void setUsdcContract(String usdcContract) { this.usdcContract = usdcContract; }
        public int getUsdcDecimals() { return usdcDecimals; }
        public void setUsdcDecimals(int usdcDecimals) { this.usdcDecimals = usdcDecimals; }
        public long getGasLimit() { return gasLimit; }
        public void setGasLimit(long gasLimit) { this.gasLimit = gasLimit; }
        public long getGasPriceMaxGwei() { return gasPriceMaxGwei; }
        public void setGasPriceMaxGwei(long gasPriceMaxGwei) { this.gasPriceMaxGwei = gasPriceMaxGwei; }
        public int getMinConfirmations() { return minConfirmations; }
        public void setMinConfirmations(int minConfirmations) { this.minConfirmations = minConfirmations; }
        public Duration getConfirmationSchedulerInterval() { return confirmationSchedulerInterval; }
        public void setConfirmationSchedulerInterval(Duration confirmationSchedulerInterval) {
            this.confirmationSchedulerInterval = confirmationSchedulerInterval;
        }
    }

    /**
     * STAGE-7-WITHDRAW Phase 3：链上出金签名配置。
     *
     * <p>{@code signing-keys.{network}.private-key-pem} 与 {@code from-address} 分别配置 ERC20 / TRC20。
     * 私钥仅启动时一次加载常驻内存；不允许日志输出或序列化。
     *
     * <p>实施策略（详见 docs/domain/状态机规范.md §7B）：
     * <ul>
     *   <li>{@code dev / staging} profile：注入 {@code LocalKmsSigner}（Phase 3 Commit 9 引入）</li>
     *   <li>未配置 / {@code prod}：默认 {@code KmsSignerStub} 占位，调用即抛 UnsupportedOperationException</li>
     * </ul>
     */
    public static class Kms {
        private final SigningKey erc20 = new SigningKey();
        private final SigningKey trc20 = new SigningKey();

        public SigningKey getErc20() {
            return erc20;
        }

        public SigningKey getTrc20() {
            return trc20;
        }

        /**
         * 按 network 字符串查 SigningKey；未知 network 返回 null。
         *
         * @param network ERC20 / TRC20
         */
        public SigningKey ofNetwork(String network) {
            if (network == null) return null;
            return switch (network) {
                case "ERC20" -> erc20;
                case "TRC20" -> trc20;
                default -> null;
            };
        }
    }

    public static class SigningKey {
        /** ECDSA secp256k1 私钥（PEM 编码或 32 字节 hex）。生产必须通过 env 注入。 */
        private String privateKeyPem;
        /** 该 network 上的平台热钱包地址（Phase 3 broadcast 使用）。 */
        private String fromAddress;

        public String getPrivateKeyPem() {
            return privateKeyPem;
        }

        public void setPrivateKeyPem(String privateKeyPem) {
            this.privateKeyPem = privateKeyPem;
        }

        public String getFromAddress() {
            return fromAddress;
        }

        public void setFromAddress(String fromAddress) {
            this.fromAddress = fromAddress;
        }
    }

    /**
     * 按链类型返回对应链节点配置。
     *
     * @param chainType 链类型
     * @return 对应的链配置
     */
    public Chain chain(ChainType chainType) {
        return switch (chainType) {
            case ETH -> chains.getEth();
            case BSC -> chains.getBsc();
            case TRON -> chains.getTron();
            case SOL -> chains.getSol();
        };
    }

    /**
     * 地址分配相关配置。
     */
    public static class Allocation {
        private int startIndex = 1;

        public int getStartIndex() {
            return startIndex;
        }

        public void setStartIndex(int startIndex) {
            this.startIndex = startIndex;
        }
    }

    /**
     * 入金地址派生配置。
     */
    public static class Derivation {
        private String ethAccountXpub;
        private String tronAccountXpub;

        public String getEthAccountXpub() {
            return ethAccountXpub;
        }

        public void setEthAccountXpub(String ethAccountXpub) {
            this.ethAccountXpub = ethAccountXpub;
        }

        public String getTronAccountXpub() {
            return tronAccountXpub;
        }

        public void setTronAccountXpub(String tronAccountXpub) {
            this.tronAccountXpub = tronAccountXpub;
        }
    }

    /**
     * Kafka 主题配置。
     */
    public static class Kafka {
        private String depositDetectedTopic = "falconx.wallet.deposit.detected";
        private String depositConfirmedTopic = "falconx.wallet.deposit.confirmed";
        private String depositReversedTopic = "falconx.wallet.deposit.reversed";
        // STAGE-5-WALLET-PROVISION
        private String identityUserRegisteredTopic = "falconx.identity.user.registered";
        private String identityUserRegisteredGroup = "falconx.wallet-service.user-registered-consumer";
        // STAGE-7-WITHDRAW Phase 3
        private String withdrawBroadcastTopic = "falconx.wallet.withdraw.broadcast";
        private String withdrawConfirmedTopic = "falconx.wallet.withdraw.confirmed";
        private String withdrawFailedTopic = "falconx.wallet.withdraw.failed";
        private String tradingWithdrawReviewedTopic = "falconx.trading.withdraw.reviewed";
        private String tradingWithdrawReviewedGroup = "falconx.wallet-service.trading-withdraw-reviewed-consumer";

        public String getWithdrawBroadcastTopic() { return withdrawBroadcastTopic; }
        public void setWithdrawBroadcastTopic(String v) { this.withdrawBroadcastTopic = v; }
        public String getWithdrawConfirmedTopic() { return withdrawConfirmedTopic; }
        public void setWithdrawConfirmedTopic(String v) { this.withdrawConfirmedTopic = v; }
        public String getWithdrawFailedTopic() { return withdrawFailedTopic; }
        public void setWithdrawFailedTopic(String v) { this.withdrawFailedTopic = v; }
        public String getTradingWithdrawReviewedTopic() { return tradingWithdrawReviewedTopic; }
        public void setTradingWithdrawReviewedTopic(String v) { this.tradingWithdrawReviewedTopic = v; }
        public String getTradingWithdrawReviewedGroup() { return tradingWithdrawReviewedGroup; }
        public void setTradingWithdrawReviewedGroup(String v) { this.tradingWithdrawReviewedGroup = v; }

        public String getDepositDetectedTopic() {
            return depositDetectedTopic;
        }

        public void setDepositDetectedTopic(String depositDetectedTopic) {
            this.depositDetectedTopic = depositDetectedTopic;
        }

        public String getDepositConfirmedTopic() {
            return depositConfirmedTopic;
        }

        public void setDepositConfirmedTopic(String depositConfirmedTopic) {
            this.depositConfirmedTopic = depositConfirmedTopic;
        }

        public String getDepositReversedTopic() {
            return depositReversedTopic;
        }

        public void setDepositReversedTopic(String depositReversedTopic) {
            this.depositReversedTopic = depositReversedTopic;
        }

        public String getIdentityUserRegisteredTopic() {
            return identityUserRegisteredTopic;
        }

        public void setIdentityUserRegisteredTopic(String identityUserRegisteredTopic) {
            this.identityUserRegisteredTopic = identityUserRegisteredTopic;
        }

        public String getIdentityUserRegisteredGroup() {
            return identityUserRegisteredGroup;
        }

        public void setIdentityUserRegisteredGroup(String identityUserRegisteredGroup) {
            this.identityUserRegisteredGroup = identityUserRegisteredGroup;
        }
    }

    /**
     * 全部链配置聚合。
     */
    public static class Chains {
        private final Chain eth = new Chain(URI.create("http://localhost:8545"), Duration.ofSeconds(5), 12, "block", "0");
        private final Chain bsc = new Chain(URI.create("http://localhost:8546"), Duration.ofSeconds(5), 15, "block", "0");
        private final Chain tron = new Chain(
                URI.create("http://localhost:8090"),
                URI.create("http://localhost:8091"),
                Duration.ofSeconds(5),
                19,
                "block",
                "0",
                null
        );
        private final Chain sol = new Chain(URI.create("http://localhost:8899"), Duration.ofSeconds(5), 32, "slot", "0");

        public Chain getEth() {
            return eth;
        }

        public Chain getBsc() {
            return bsc;
        }

        public Chain getTron() {
            return tron;
        }

        public Chain getSol() {
            return sol;
        }
    }

    /**
     * 单条链的节点、确认与游标配置。
     */
    public static class Chain {
        private URI rpcUrl = URI.create("http://localhost");
        private URI solidityRpcUrl;
        private Duration scanInterval = Duration.ofSeconds(5);
        private int requiredConfirmations = 12;
        private String cursorType = "block";
        private String initialCursor = "0";
        // 2026-05-27 FX-074-B：单轮 sync 最大扫块跨度。cursor 落后过多（如停机 22h ≈ 3344 块）时
        // 限批分批追赶，避免一次性几千次 RPC 打爆 Alchemy 429 CU/s 后 cursor 永久卡死。
        // 0 或负数 = 不限批（兼容旧行为）。EVM 默认 500 块/轮。
        private int maxScanBlocksPerSync = 500;
        private String apiKey;
        private String usdtContract;
        private int usdtDecimals = 6;
        private String usdcContract;
        private int usdcDecimals = 6;

        public Chain() {
        }

        public Chain(URI rpcUrl, Duration scanInterval, int requiredConfirmations, String cursorType, String initialCursor) {
            this(rpcUrl, null, scanInterval, requiredConfirmations, cursorType, initialCursor, null);
        }

        public Chain(URI rpcUrl,
                     URI solidityRpcUrl,
                     Duration scanInterval,
                     int requiredConfirmations,
                     String cursorType,
                     String initialCursor,
                     String apiKey) {
            this.rpcUrl = rpcUrl;
            this.solidityRpcUrl = solidityRpcUrl;
            this.scanInterval = scanInterval;
            this.requiredConfirmations = requiredConfirmations;
            this.cursorType = cursorType;
            this.initialCursor = initialCursor;
            this.apiKey = apiKey;
        }

        public URI getRpcUrl() {
            return rpcUrl;
        }

        public void setRpcUrl(URI rpcUrl) {
            this.rpcUrl = rpcUrl;
        }

        public URI getSolidityRpcUrl() {
            return solidityRpcUrl;
        }

        public void setSolidityRpcUrl(URI solidityRpcUrl) {
            this.solidityRpcUrl = solidityRpcUrl;
        }

        public Duration getScanInterval() {
            return scanInterval;
        }

        public void setScanInterval(Duration scanInterval) {
            this.scanInterval = scanInterval;
        }

        public int getRequiredConfirmations() {
            return requiredConfirmations;
        }

        public void setRequiredConfirmations(int requiredConfirmations) {
            this.requiredConfirmations = requiredConfirmations;
        }

        public int getMaxScanBlocksPerSync() {
            return maxScanBlocksPerSync;
        }

        public void setMaxScanBlocksPerSync(int maxScanBlocksPerSync) {
            this.maxScanBlocksPerSync = maxScanBlocksPerSync;
        }

        public String getCursorType() {
            return cursorType;
        }

        public void setCursorType(String cursorType) {
            this.cursorType = cursorType;
        }

        public String getInitialCursor() {
            return initialCursor;
        }

        public void setInitialCursor(String initialCursor) {
            this.initialCursor = initialCursor;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getUsdtContract() {
            return usdtContract;
        }

        public void setUsdtContract(String usdtContract) {
            this.usdtContract = usdtContract;
        }

        public int getUsdtDecimals() {
            return usdtDecimals;
        }

        public void setUsdtDecimals(int usdtDecimals) {
            this.usdtDecimals = usdtDecimals;
        }

        public String getUsdcContract() {
            return usdcContract;
        }

        public void setUsdcContract(String usdcContract) {
            this.usdcContract = usdcContract;
        }

        public int getUsdcDecimals() {
            return usdcDecimals;
        }

        public void setUsdcDecimals(int usdcDecimals) {
            this.usdcDecimals = usdcDecimals;
        }

        public List<TokenContract> tokenContracts() {
            List<TokenContract> contracts = new ArrayList<>();
            if (hasText(usdtContract)) {
                contracts.add(new TokenContract("USDT", usdtContract, usdtDecimals));
            }
            if (hasText(usdcContract)) {
                contracts.add(new TokenContract("USDC", usdcContract, usdcDecimals));
            }
            return contracts;
        }

        public Optional<TokenContract> findTokenContract(String contractAddress) {
            if (!hasText(contractAddress)) {
                return Optional.empty();
            }
            String normalized = contractAddress.toLowerCase(Locale.ROOT);
            return tokenContracts().stream()
                    .filter(token -> token.contractAddress().toLowerCase(Locale.ROOT).equals(normalized))
                    .findFirst();
        }
    }

    public record TokenContract(String symbol, String contractAddress, int decimals) {
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * STAGE-2-DEPOSIT R4：internal API token 配置。
     *
     * <p>console-service 通过 gateway 调用 wallet-service 的 {@code /internal/v1/wallet/**} 时携带
     * {@code X-Internal-Token}；{@link com.falconx.wallet.security.WalletInternalApiTokenFilter} 校验。
     */
    public static class InternalApi {
        private String token;

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }
}
