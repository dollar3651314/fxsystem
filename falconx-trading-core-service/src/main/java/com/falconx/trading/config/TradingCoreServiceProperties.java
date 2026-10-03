package com.falconx.trading.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * trading-core-service 配置属性。
 *
 * <p>该配置类用于冻结 Stage 3B 交易核心骨架在本地运行时所需的最小业务参数，
 * 包括结算币种、手续费率与维持保证金率。
 * 品种支持校验不再使用本地静态白名单，而是依赖 market-service 写入的 Redis 交易时间快照，
 * 避免交易核心与 market owner 的产品配置发生分叉。
 */
@ConfigurationProperties(prefix = "falconx.trading")
public class TradingCoreServiceProperties {

    private String settlementToken = "USDT";
    /**
     * 入金 token 白名单。trading-core 账户按 USDT 计价并按 1:1 入账，
     * 任何不在该白名单的 token（如原生 ETH/BNB 等 native gas，或未审计的 ERC20）
     * 都会被 deposit consumer 直接拒收，避免 1 ETH = $3000 被错记成 1 USDT。
     *
     * <p>测试环境用 Sepolia USDC 代用 USDT，因此默认放 USDC 进白名单；
     * 生产环境上线前应收紧为 ["USDT"] + 一次性 migration 切换。
     */
    private Set<String> depositTokenWhitelist = new LinkedHashSet<>(List.of("USDT", "USDC"));
    private BigDecimal defaultFeeRate = new BigDecimal("0.0005");
    private BigDecimal maintenanceMarginRate = new BigDecimal("0.005");
    private BigDecimal maxLeverage = new BigDecimal("100");
    private final Cache cache = new Cache();
    private final Stale stale = new Stale();
    private final Swap swap = new Swap();
    private final Kafka kafka = new Kafka();
    private final InternalApi internalApi = new InternalApi();
    private final ExternalRpc externalRpc = new ExternalRpc();
    private final Withdraw withdraw = new Withdraw();
    private final Fx fx = new Fx();
    private final Tier tier = new Tier();
    private final FxPause fxPause = new FxPause();
    private final MarginMode marginMode = new MarginMode();

    public String getSettlementToken() {
        return settlementToken;
    }

    public void setSettlementToken(String settlementToken) {
        this.settlementToken = settlementToken;
    }

    public Set<String> getDepositTokenWhitelist() {
        return depositTokenWhitelist;
    }

    public void setDepositTokenWhitelist(Set<String> depositTokenWhitelist) {
        // 大小写归一化 + null 防御，避免 yaml 写小写或带空白时跟 token symbol 比对失败
        if (depositTokenWhitelist == null) {
            this.depositTokenWhitelist = new LinkedHashSet<>();
            return;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String token : depositTokenWhitelist) {
            if (token == null) continue;
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed.toUpperCase(Locale.ROOT));
            }
        }
        this.depositTokenWhitelist = normalized;
    }

    /** 大小写无关白名单判断。 */
    public boolean isDepositTokenAllowed(String token) {
        if (token == null) return false;
        return depositTokenWhitelist.contains(token.trim().toUpperCase(Locale.ROOT));
    }

    public BigDecimal getDefaultFeeRate() {
        return defaultFeeRate;
    }

    public void setDefaultFeeRate(BigDecimal defaultFeeRate) {
        this.defaultFeeRate = defaultFeeRate;
    }

    public BigDecimal getMaintenanceMarginRate() {
        return maintenanceMarginRate;
    }

    public void setMaintenanceMarginRate(BigDecimal maintenanceMarginRate) {
        this.maintenanceMarginRate = maintenanceMarginRate;
    }

    public BigDecimal getMaxLeverage() {
        return maxLeverage;
    }

    public void setMaxLeverage(BigDecimal maxLeverage) {
        this.maxLeverage = maxLeverage;
    }

    public Stale getStale() {
        return stale;
    }

    public Cache getCache() {
        return cache;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public Swap getSwap() {
        return swap;
    }

    /**
     * trading-core Redis 缓存约束。
     *
     * <p>当前阶段只冻结“最新报价快照”这一类 Redis 缓存的 TTL，
     * 让报价写入路径具备可回收的生命周期，而不是永久残留历史 key。
     */
    public static class Cache {
        private Duration quoteTtl = Duration.ofSeconds(10);

        public Duration getQuoteTtl() {
            return quoteTtl;
        }

        public void setQuoteTtl(Duration quoteTtl) {
            this.quoteTtl = quoteTtl;
        }
    }

    /**
     * 交易域报价时效配置。
     *
     * <p>trading-core 读取 Redis 最新价时，不能只依赖 market 写入瞬间计算出的 `stale` 布尔值。
     * 若外部源中断，原本 fresh 的报价随着时间推移也必须在读取时自动转为 stale，
     * 这样下单和风控链路才能持续遵守“超过 5 秒不可用”的统一规则。
     */
    public static class Stale {
        private Duration maxAge = Duration.ofSeconds(5);

        public Duration getMaxAge() {
            return maxAge;
        }

        public void setMaxAge(Duration maxAge) {
            this.maxAge = maxAge;
        }
    }

    /**
     * 交易域隔夜利息结算调度配置。
     *
     * <p>一期 `Swap` 结算固定由 `trading-core-service` 本地定时扫描触发，
     * 调度默认按 `UTC` 每秒执行一次，尽量贴近 `rollover` 时点抓取 fresh 价格，
     * 避免使用超出时效窗口的后续报价误算 `Swap`。
     */
    public static class Swap {
        private boolean enabled = true;
        private String settlementCron = "*/1 * * * * *";
        private String settlementZone = "UTC";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSettlementCron() {
            return settlementCron;
        }

        public void setSettlementCron(String settlementCron) {
            this.settlementCron = settlementCron;
        }

        public String getSettlementZone() {
            return settlementZone;
        }

        public void setSettlementZone(String settlementZone) {
            this.settlementZone = settlementZone;
        }
    }

    /**
     * trading-core Kafka 主题与消费组配置。
     *
     * <p>交易核心既消费市场和钱包事件，也通过 Outbox 对外发布低频关键业务事件，
     * 因此这里统一固定输入 topic、输出 topic 与消费组名称，避免字符串散落在 listener / producer 中。
     */
    public static class Kafka {
        private String marketPriceTickTopic = "falconx.market.price.tick";
        private String marketKlineUpdateTopic = "falconx.market.kline.update";
        private String walletDepositConfirmedTopic = "falconx.wallet.deposit.confirmed";
        private String walletDepositReversedTopic = "falconx.wallet.deposit.reversed";
        private String depositCreditedTopic = "falconx.trading.deposit.credited";
        // STAGE-6-KYC Phase 4：trading-core 消费 identity 端 KYC 审核完成事件 → 站内信
        private String kycReviewedTopic = "falconx.identity.kyc.reviewed";
        private String kycReviewedConsumerGroupId = "falconx.trading-core-service.kyc-reviewed-consumer-group";
        // STAGE-7-WITHDRAW Phase 3：消费 wallet 端 3 个出金事件
        private String walletWithdrawBroadcastTopic = "falconx.wallet.withdraw.broadcast";
        private String walletWithdrawBroadcastConsumerGroupId = "falconx.trading-core-service.wallet-withdraw-broadcast-consumer-group";
        private String walletWithdrawConfirmedTopic = "falconx.wallet.withdraw.confirmed";
        private String walletWithdrawConfirmedConsumerGroupId = "falconx.trading-core-service.wallet-withdraw-confirmed-consumer-group";
        private String walletWithdrawFailedTopic = "falconx.wallet.withdraw.failed";
        private String walletWithdrawFailedConsumerGroupId = "falconx.trading-core-service.wallet-withdraw-failed-consumer-group";
        private String consumerGroupId = "falconx-trading-core-service";
        private int marketPriceTickWorkerCount = 4;

        public String getMarketPriceTickTopic() {
            return marketPriceTickTopic;
        }

        public void setMarketPriceTickTopic(String marketPriceTickTopic) {
            this.marketPriceTickTopic = marketPriceTickTopic;
        }

        public String getMarketKlineUpdateTopic() {
            return marketKlineUpdateTopic;
        }

        public void setMarketKlineUpdateTopic(String marketKlineUpdateTopic) {
            this.marketKlineUpdateTopic = marketKlineUpdateTopic;
        }

        public String getWalletDepositConfirmedTopic() {
            return walletDepositConfirmedTopic;
        }

        public void setWalletDepositConfirmedTopic(String walletDepositConfirmedTopic) {
            this.walletDepositConfirmedTopic = walletDepositConfirmedTopic;
        }

        public String getWalletDepositReversedTopic() {
            return walletDepositReversedTopic;
        }

        public void setWalletDepositReversedTopic(String walletDepositReversedTopic) {
            this.walletDepositReversedTopic = walletDepositReversedTopic;
        }

        public String getDepositCreditedTopic() {
            return depositCreditedTopic;
        }

        public void setDepositCreditedTopic(String depositCreditedTopic) {
            this.depositCreditedTopic = depositCreditedTopic;
        }

        public String getConsumerGroupId() {
            return consumerGroupId;
        }

        public void setConsumerGroupId(String consumerGroupId) {
            this.consumerGroupId = consumerGroupId;
        }

        public int getMarketPriceTickWorkerCount() {
            return marketPriceTickWorkerCount;
        }

        public void setMarketPriceTickWorkerCount(int marketPriceTickWorkerCount) {
            this.marketPriceTickWorkerCount = marketPriceTickWorkerCount;
        }

        public String getKycReviewedTopic() {
            return kycReviewedTopic;
        }

        public void setKycReviewedTopic(String kycReviewedTopic) {
            this.kycReviewedTopic = kycReviewedTopic;
        }

        public String getKycReviewedConsumerGroupId() {
            return kycReviewedConsumerGroupId;
        }

        public void setKycReviewedConsumerGroupId(String kycReviewedConsumerGroupId) {
            this.kycReviewedConsumerGroupId = kycReviewedConsumerGroupId;
        }

        public String getWalletWithdrawBroadcastTopic() { return walletWithdrawBroadcastTopic; }
        public void setWalletWithdrawBroadcastTopic(String v) { this.walletWithdrawBroadcastTopic = v; }
        public String getWalletWithdrawBroadcastConsumerGroupId() { return walletWithdrawBroadcastConsumerGroupId; }
        public void setWalletWithdrawBroadcastConsumerGroupId(String v) { this.walletWithdrawBroadcastConsumerGroupId = v; }
        public String getWalletWithdrawConfirmedTopic() { return walletWithdrawConfirmedTopic; }
        public void setWalletWithdrawConfirmedTopic(String v) { this.walletWithdrawConfirmedTopic = v; }
        public String getWalletWithdrawConfirmedConsumerGroupId() { return walletWithdrawConfirmedConsumerGroupId; }
        public void setWalletWithdrawConfirmedConsumerGroupId(String v) { this.walletWithdrawConfirmedConsumerGroupId = v; }
        public String getWalletWithdrawFailedTopic() { return walletWithdrawFailedTopic; }
        public void setWalletWithdrawFailedTopic(String v) { this.walletWithdrawFailedTopic = v; }
        public String getWalletWithdrawFailedConsumerGroupId() { return walletWithdrawFailedConsumerGroupId; }
        public void setWalletWithdrawFailedConsumerGroupId(String v) { this.walletWithdrawFailedConsumerGroupId = v; }
    }

    public InternalApi getInternalApi() {
        return internalApi;
    }

    public ExternalRpc getExternalRpc() {
        return externalRpc;
    }

    public Withdraw getWithdraw() {
        return withdraw;
    }

    public Fx getFx() {
        return fx;
    }

    public Tier getTier() {
        return tier;
    }

    public FxPause getFxPause() {
        return fxPause;
    }

    public MarginMode getMarginMode() {
        return marginMode;
    }

    /**
     * STAGE-14D1 Task 3：用户级 margin mode 切换参数。
     *
     * <p>切换成功后写入 {@code t_account.mode_cooling_until = now + coolingDuration}，
     * 冷静期内重复切换被 30082 拒绝。默认 5min（照搬 master §6.1，不依赖 config SDK；
     * admin 可配 60s-7d 留 D3 console 配置 UI）。
     */
    public static class MarginMode {
        /** margin mode 切换冷静期时长，默认 5 分钟。 */
        private Duration coolingDuration = Duration.ofMinutes(5);

        public Duration getCoolingDuration() {
            return coolingDuration;
        }

        public void setCoolingDuration(Duration coolingDuration) {
            this.coolingDuration = coolingDuration;
        }
    }

    /**
     * STAGE-14C1 Task 5：杠杆/MM 档位本地缓存配置。
     *
     * <p>{@code LeverageTierResolver} 按 (symbol, groupCode) 缓存档位列表，避免每次开仓都查 DB。
     * 缓存三要素（AGENTS §3.9）：TTL=30s（本配置项）；刷新=惰性过期重查；
     * 降级=cache miss 查 DB，DB 查空返回 empty（caller 判 empty → 30072）。
     */
    public static class Tier {
        /** 档位本地缓存 TTL（秒），默认 30。 */
        private int cacheRefreshSeconds = 30;

        public int getCacheRefreshSeconds() {
            return cacheRefreshSeconds;
        }

        public void setCacheRefreshSeconds(int cacheRefreshSeconds) {
            this.cacheRefreshSeconds = cacheRefreshSeconds;
        }
    }

    /**
     * STAGE-14C2 Task 5：FX_PAUSED 类目行为开关本地缓存配置。
     *
     * <p>{@code FxPauseBehaviorRepository} 全量加载 {@code t_fx_pause_behavior}（8 行小数据）到
     * {@code Map<category, FxPauseBehavior>}，避免每次停盘判定都查 DB。缓存三要素（AGENTS §3.9）：
     * TTL={@code cacheRefreshSeconds}（默认 60s）；刷新=惰性过期（读到过期快照即全量回源重填）；
     * 降级=缓存命中后 DB 无该 category → empty（caller Task 6 降级 allow_all）。
     */
    public static class FxPause {
        /** 类目行为开关全量快照本地缓存 TTL（秒），默认 60。 */
        private int cacheRefreshSeconds = 60;

        public int getCacheRefreshSeconds() {
            return cacheRefreshSeconds;
        }

        public void setCacheRefreshSeconds(int cacheRefreshSeconds) {
            this.cacheRefreshSeconds = cacheRefreshSeconds;
        }
    }

    /**
     * STAGE-7-WITHDRAW：trading-core 调外部服务（identity / wallet）internal RPC 配置。
     *
     * <p>调用链：trading-core → gateway:18080 → identity/wallet。gateway 注入 {@code X-Internal-Token}；
     * trading-core 也在 client 侧主动注入（保证 dev 直连场景也工作）。
     */
    public static class ExternalRpc {
        private String gatewayBaseUrl = "http://localhost:18080";
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(5);
        /**
         * service-to-service 调用方标识；wallet/identity internal filter 强制 X-Admin-User-Id
         * 存在，service caller 传递服务级标识值（非真实 admin user）。
         */
        private long serviceCallerId = 0L;

        public String getGatewayBaseUrl() {
            return gatewayBaseUrl;
        }

        public void setGatewayBaseUrl(String gatewayBaseUrl) {
            this.gatewayBaseUrl = gatewayBaseUrl;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public long getServiceCallerId() {
            return serviceCallerId;
        }

        public void setServiceCallerId(long serviceCallerId) {
            this.serviceCallerId = serviceCallerId;
        }
    }

    /**
     * STAGE-7-WITHDRAW：出金参数（单笔/单日/冷静期等）。
     */
    public static class Withdraw {
        /** 单笔上限（USDT，1:1 USD），超出 → 30041。 */
        private BigDecimal singleLimitUsd = new BigDecimal("10000");
        /** 单日累计上限（USDT，1:1 USD），≥ → 30042。 */
        private BigDecimal dailyLimitUsd = new BigDecimal("30000");
        /** 单笔最小金额，&lt; → 30040。 */
        private BigDecimal minAmount = new BigDecimal("10");
        /** 大额延迟阈值（≥ 进入 APPROVED_DELAYED 路径）。 */
        private BigDecimal delayedAmountThresholdUsd = new BigDecimal("3000");
        /** 冷静期时长。 */
        private Duration coolingDuration = Duration.ofHours(2);
        /** 大额延迟时长。 */
        private Duration delayedDuration = Duration.ofHours(6);
        /** 白名单冷静期（24h）。 */
        private Duration whitelistCoolingDuration = Duration.ofHours(24);

        public BigDecimal getSingleLimitUsd() {
            return singleLimitUsd;
        }

        public void setSingleLimitUsd(BigDecimal singleLimitUsd) {
            this.singleLimitUsd = singleLimitUsd;
        }

        public BigDecimal getDailyLimitUsd() {
            return dailyLimitUsd;
        }

        public void setDailyLimitUsd(BigDecimal dailyLimitUsd) {
            this.dailyLimitUsd = dailyLimitUsd;
        }

        public BigDecimal getMinAmount() {
            return minAmount;
        }

        public void setMinAmount(BigDecimal minAmount) {
            this.minAmount = minAmount;
        }

        public BigDecimal getDelayedAmountThresholdUsd() {
            return delayedAmountThresholdUsd;
        }

        public void setDelayedAmountThresholdUsd(BigDecimal delayedAmountThresholdUsd) {
            this.delayedAmountThresholdUsd = delayedAmountThresholdUsd;
        }

        public Duration getCoolingDuration() {
            return coolingDuration;
        }

        public void setCoolingDuration(Duration coolingDuration) {
            this.coolingDuration = coolingDuration;
        }

        public Duration getDelayedDuration() {
            return delayedDuration;
        }

        public void setDelayedDuration(Duration delayedDuration) {
            this.delayedDuration = delayedDuration;
        }

        public Duration getWhitelistCoolingDuration() {
            return whitelistCoolingDuration;
        }

        public void setWhitelistCoolingDuration(Duration whitelistCoolingDuration) {
            this.whitelistCoolingDuration = whitelistCoolingDuration;
        }
    }

    /**
     * STAGE-14B Task 3：trading-core 消费 market FX 数据配置。
     */
    public static class Fx {
        /** market-service 直接 RPC base URL（当前通过 gateway 路由，与 external-rpc.gateway-base-url 保持一致）。 */
        private String marketRpcUrl = "http://localhost:18080";
        /** FX rate 更新 Kafka topic。 */
        private String fxRateUpdateTopic = "falconx.market.fx.rate.update";
        /** FX rate 消费组。 */
        private String consumerGroupId = "falconx-trading-fx-rate";
        /** 启动 RPC 失败策略：warn（仅 warn，不阻断启动）。 */
        private String bootstrapFailurePolicy = "warn";

        public String getMarketRpcUrl() { return marketRpcUrl; }
        public void setMarketRpcUrl(String marketRpcUrl) { this.marketRpcUrl = marketRpcUrl; }
        public String getFxRateUpdateTopic() { return fxRateUpdateTopic; }
        public void setFxRateUpdateTopic(String fxRateUpdateTopic) { this.fxRateUpdateTopic = fxRateUpdateTopic; }
        public String getConsumerGroupId() { return consumerGroupId; }
        public void setConsumerGroupId(String consumerGroupId) { this.consumerGroupId = consumerGroupId; }
        public String getBootstrapFailurePolicy() { return bootstrapFailurePolicy; }
        public void setBootstrapFailurePolicy(String bootstrapFailurePolicy) { this.bootstrapFailurePolicy = bootstrapFailurePolicy; }
    }

    /**
     * 跨服务 internal RPC 鉴权配置（STAGE-2-CUSTOMER）。
     *
     * <p>console-service 通过 gateway 调用 trading-core 的 {@code /internal/v1/trading/**} 时携带
     * {@code X-Internal-Token}，trading-core filter 校验。详细机制见
     * {@code docs/architecture/管理端架构.md} §4.1。
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
