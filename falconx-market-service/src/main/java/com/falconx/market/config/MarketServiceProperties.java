package com.falconx.market.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * market-service 配置属性。
 *
 * <p>当前配置类用于冻结 LP、Redis、ClickHouse、Kafka 相关参数形态，
 * 避免实现和文档再次分叉。
 */
@ConfigurationProperties(prefix = "falconx.market")
public class MarketServiceProperties {

    private final Lp lp = new Lp();
    private final Redis redis = new Redis();
    private final Analytics analytics = new Analytics();
    private final Kafka kafka = new Kafka();
    private final Stale stale = new Stale();
    private final QuoteQuality quoteQuality = new QuoteQuality();
    private final Kline kline = new Kline();
    private final WebSocket webSocket = new WebSocket();
    private final InternalApi internalApi = new InternalApi();
    private final Fx fx = new Fx();

    public Lp getLp() {
        return lp;
    }

    public Fx getFx() {
        return fx;
    }

    public InternalApi getInternalApi() {
        return internalApi;
    }

    /**
     * FX 汇率子域参数（STAGE-14A）。
     */
    public static class Fx {
        /** 超过此秒数未收到 FX tick，视为 stale（触发 FX_RATE_STALE 60011）。 */
        private int staleThresholdSeconds = 30;
        /** 超过此秒数未收到 FX tick，暂停该 symbol 的报价推送（保护下游）。 */
        private int pauseThresholdSeconds = 300;
        /** 向 Kafka 发布 FX rate 的节流间隔（毫秒），默认 1000ms = 1Hz。对应 @Scheduled fixedRateString。 */
        private int kafkaThrottleIntervalMs = 1000;
        /** FX rate 写入 Redis 的 TTL（秒）。 */
        private int redisTtlSeconds = 5;

        public int getStaleThresholdSeconds() {
            return staleThresholdSeconds;
        }

        public void setStaleThresholdSeconds(int staleThresholdSeconds) {
            this.staleThresholdSeconds = staleThresholdSeconds;
        }

        public int getPauseThresholdSeconds() {
            return pauseThresholdSeconds;
        }

        public void setPauseThresholdSeconds(int pauseThresholdSeconds) {
            this.pauseThresholdSeconds = pauseThresholdSeconds;
        }

        public int getKafkaThrottleIntervalMs() {
            return kafkaThrottleIntervalMs;
        }

        public void setKafkaThrottleIntervalMs(int kafkaThrottleIntervalMs) {
            this.kafkaThrottleIntervalMs = kafkaThrottleIntervalMs;
        }

        public int getRedisTtlSeconds() {
            return redisTtlSeconds;
        }

        public void setRedisTtlSeconds(int redisTtlSeconds) {
            this.redisTtlSeconds = redisTtlSeconds;
        }
    }

    /**
     * STAGE-2-SYMBOL: 跨服务 internal RPC token 配置。
     */
    public static class InternalApi {
        private String token = "";

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

    public Redis getRedis() {
        return redis;
    }

    public Analytics getAnalytics() {
        return analytics;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public Stale getStale() {
        return stale;
    }

    public QuoteQuality getQuoteQuality() {
        return quoteQuality;
    }

    public Kline getKline() {
        return kline;
    }

    public WebSocket getWebSocket() {
        return webSocket;
    }

    /**
     * 自建 LP 外部报价源配置。
     */
    public static class Lp {
        private boolean enabled = true;
        private String code = "GODSA";
        private String domain = "";
        private String socketPath = "/safe/socket.io/";
        private String appId = "";
        private String token = "";
        private String secretKey = "";
        private int serverId;
        private int metaTradeVersion = 4;
        private int metaTraderId;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration reconnectInterval = Duration.ofSeconds(5);
        private String symbolWhitelistRefreshCron = "0 */1 * * * *";
        private String symbolWhitelistRefreshZone = "UTC";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getDomain() {
            return domain;
        }

        public void setDomain(String domain) {
            this.domain = domain;
        }

        public String getSocketPath() {
            return socketPath;
        }

        public void setSocketPath(String socketPath) {
            this.socketPath = socketPath;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public int getServerId() {
            return serverId;
        }

        public void setServerId(int serverId) {
            this.serverId = serverId;
        }

        public int getMetaTradeVersion() {
            return metaTradeVersion;
        }

        public void setMetaTradeVersion(int metaTradeVersion) {
            this.metaTradeVersion = metaTradeVersion;
        }

        public int getMetaTraderId() {
            return metaTraderId;
        }

        public void setMetaTraderId(int metaTraderId) {
            this.metaTraderId = metaTraderId;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReconnectInterval() {
            return reconnectInterval;
        }

        public void setReconnectInterval(Duration reconnectInterval) {
            this.reconnectInterval = reconnectInterval;
        }

        public String getSymbolWhitelistRefreshCron() {
            return symbolWhitelistRefreshCron;
        }

        public void setSymbolWhitelistRefreshCron(String symbolWhitelistRefreshCron) {
            this.symbolWhitelistRefreshCron = symbolWhitelistRefreshCron;
        }

        public String getSymbolWhitelistRefreshZone() {
            return symbolWhitelistRefreshZone;
        }

        public void setSymbolWhitelistRefreshZone(String symbolWhitelistRefreshZone) {
            this.symbolWhitelistRefreshZone = symbolWhitelistRefreshZone;
        }
    }

    /**
     * Redis 行情缓存约束。
     */
    public static class Redis {
        private Duration quoteTtl = Duration.ofSeconds(10);
        private Duration referenceQuoteTtl = Duration.ofDays(30);
        private Duration tradingScheduleTtl = Duration.ofHours(25);
        private String tradingScheduleRefreshCron = "0 0 0 * * *";
        private String tradingScheduleRefreshZone = "UTC";
        private Duration swapRateTtl = Duration.ofHours(25);
        private String swapRateRefreshCron = "0 0 0 * * *";
        private String swapRateRefreshZone = "UTC";

        public Duration getQuoteTtl() {
            return quoteTtl;
        }

        public void setQuoteTtl(Duration quoteTtl) {
            this.quoteTtl = quoteTtl;
        }

        public Duration getReferenceQuoteTtl() {
            return referenceQuoteTtl;
        }

        public void setReferenceQuoteTtl(Duration referenceQuoteTtl) {
            this.referenceQuoteTtl = referenceQuoteTtl;
        }

        public Duration getTradingScheduleTtl() {
            return tradingScheduleTtl;
        }

        public void setTradingScheduleTtl(Duration tradingScheduleTtl) {
            this.tradingScheduleTtl = tradingScheduleTtl;
        }

        public String getTradingScheduleRefreshCron() {
            return tradingScheduleRefreshCron;
        }

        public void setTradingScheduleRefreshCron(String tradingScheduleRefreshCron) {
            this.tradingScheduleRefreshCron = tradingScheduleRefreshCron;
        }

        public String getTradingScheduleRefreshZone() {
            return tradingScheduleRefreshZone;
        }

        public void setTradingScheduleRefreshZone(String tradingScheduleRefreshZone) {
            this.tradingScheduleRefreshZone = tradingScheduleRefreshZone;
        }

        public Duration getSwapRateTtl() {
            return swapRateTtl;
        }

        public void setSwapRateTtl(Duration swapRateTtl) {
            this.swapRateTtl = swapRateTtl;
        }

        public String getSwapRateRefreshCron() {
            return swapRateRefreshCron;
        }

        public void setSwapRateRefreshCron(String swapRateRefreshCron) {
            this.swapRateRefreshCron = swapRateRefreshCron;
        }

        public String getSwapRateRefreshZone() {
            return swapRateRefreshZone;
        }

        public void setSwapRateRefreshZone(String swapRateRefreshZone) {
            this.swapRateRefreshZone = swapRateRefreshZone;
        }
    }

    /**
     * ClickHouse 分析存储相关配置。
     */
    public static class Analytics {
        private String database = "falconx_market_analytics";
        private int quoteBatchSize = 200;
        private Duration quoteFlushInterval = Duration.ofSeconds(10);
        /**
         * 进程内 quote tick 缓冲队列的最大容量上限。
         * <p>历史上没有该上限，ClickHouse 卡顿/不可用时 LP 仍在 push tick，
         * 队列无界增长直至堆耗尽 OOM（2026-05-15 ~ 05-20 持续发生）。
         * <p>500_000 条按平均 ~250 B/条估算 ~125 MB，与 2 GB heap 比留有充足余量。
         * 触达上限时丢弃 <strong>最早</strong>的 tick（drop oldest），保新数据。
         */
        private int quoteQueueMaxSize = 500_000;
        /**
         * K 线批量入库（#2，2026-06-08）。原本每根收盘 K 线逐行 insert，整分钟边界 ~1571 个
         * 1m bucket 同时收盘 → part 爆炸 → merge 狂潮（~1993 次/小时）+ 读放大。改为缓冲攒批。
         * 仅影响 ClickHouse 入库频率，<strong>不影响</strong> WebSocket 实时推送（走独立路径）。
         * <p>batchSize=1000：整分钟 ~1571 根 2 批写完；flushInterval=1s：收盘后最多 1s 落库；
         * queueMaxSize=100_000：丢一根 K 线 = ReplacingMergeTree 不会重建的永久缺口，故 cap 设高、量小不触顶。
         */
        private int klineBatchSize = 1000;
        private Duration klineFlushInterval = Duration.ofSeconds(1);
        private int klineQueueMaxSize = 100_000;
        private String jdbcUrl = "jdbc:clickhouse://localhost:8123/falconx_market_analytics";
        private String username = "default";
        private String password;

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public int getQuoteBatchSize() {
            return quoteBatchSize;
        }

        public void setQuoteBatchSize(int quoteBatchSize) {
            this.quoteBatchSize = quoteBatchSize;
        }

        public Duration getQuoteFlushInterval() {
            return quoteFlushInterval;
        }

        public void setQuoteFlushInterval(Duration quoteFlushInterval) {
            this.quoteFlushInterval = quoteFlushInterval;
        }

        public int getQuoteQueueMaxSize() {
            return quoteQueueMaxSize;
        }

        public void setQuoteQueueMaxSize(int quoteQueueMaxSize) {
            this.quoteQueueMaxSize = quoteQueueMaxSize;
        }

        public int getKlineBatchSize() {
            return klineBatchSize;
        }

        public void setKlineBatchSize(int klineBatchSize) {
            this.klineBatchSize = klineBatchSize;
        }

        public Duration getKlineFlushInterval() {
            return klineFlushInterval;
        }

        public void setKlineFlushInterval(Duration klineFlushInterval) {
            this.klineFlushInterval = klineFlushInterval;
        }

        public int getKlineQueueMaxSize() {
            return klineQueueMaxSize;
        }

        public void setKlineQueueMaxSize(int klineQueueMaxSize) {
            this.klineQueueMaxSize = klineQueueMaxSize;
        }

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public void setJdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    /**
     * Kafka 主题配置。
     */
    public static class Kafka {
        private String priceTickTopic = "falconx.market.price.tick";
        private String klineUpdateTopic = "falconx.market.kline.update";
        /** STAGE-14A Task 7: FX rate 1Hz 节流发布目标 topic。 */
        private String fxRateUpdateTopic = "falconx.market.fx.rate.update";

        public String getPriceTickTopic() {
            return priceTickTopic;
        }

        public void setPriceTickTopic(String priceTickTopic) {
            this.priceTickTopic = priceTickTopic;
        }

        public String getKlineUpdateTopic() {
            return klineUpdateTopic;
        }

        public void setKlineUpdateTopic(String klineUpdateTopic) {
            this.klineUpdateTopic = klineUpdateTopic;
        }

        public String getFxRateUpdateTopic() {
            return fxRateUpdateTopic;
        }

        public void setFxRateUpdateTopic(String fxRateUpdateTopic) {
            this.fxRateUpdateTopic = fxRateUpdateTopic;
        }
    }

    /**
     * stale 判定参数。
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
     * 行情质量保护参数。
     *
     * <p>所有比率阈值均使用小数形式（0.05 = 5%）。设为 null 或 0 表示禁用该项检测。
     */
    public static class QuoteQuality {
        /** 同一 symbol bid/ask 连续不变超过此时长视为 NO_QUOTE */
        private Duration unchangedMaxAge = Duration.ofMinutes(1);
        /** 点差相对 mid 的最大允许比率，超过视为 ABNORMAL/SPREAD_TOO_LARGE */
        private java.math.BigDecimal maxSpreadRate = null;
        /** 单 tick 相对前一 mid 的最大允许跳变比率，超过视为 ABNORMAL/TICK_JUMP_TOO_LARGE */
        private java.math.BigDecimal maxTickJumpRate = null;
        /** 短时波动滑动窗口内 (max-min)/min 的最大允许比率，超过视为 ABNORMAL/SHORT_TERM_VOLATILITY_EXCEEDED */
        private java.math.BigDecimal maxVolatilityRate = null;
        /** 短时波动检测使用的滑动窗口时长 */
        private Duration volatilityWindow = Duration.ofSeconds(60);

        public Duration getUnchangedMaxAge() {
            return unchangedMaxAge;
        }

        public void setUnchangedMaxAge(Duration unchangedMaxAge) {
            this.unchangedMaxAge = unchangedMaxAge;
        }

        public java.math.BigDecimal getMaxSpreadRate() {
            return maxSpreadRate;
        }

        public void setMaxSpreadRate(java.math.BigDecimal maxSpreadRate) {
            this.maxSpreadRate = maxSpreadRate;
        }

        public java.math.BigDecimal getMaxTickJumpRate() {
            return maxTickJumpRate;
        }

        public void setMaxTickJumpRate(java.math.BigDecimal maxTickJumpRate) {
            this.maxTickJumpRate = maxTickJumpRate;
        }

        public java.math.BigDecimal getMaxVolatilityRate() {
            return maxVolatilityRate;
        }

        public void setMaxVolatilityRate(java.math.BigDecimal maxVolatilityRate) {
            this.maxVolatilityRate = maxVolatilityRate;
        }

        public Duration getVolatilityWindow() {
            return volatilityWindow;
        }

        public void setVolatilityWindow(Duration volatilityWindow) {
            this.volatilityWindow = volatilityWindow;
        }
    }

    /**
     * K 线聚合参数。
     */
    public static class Kline {
        private List<String> intervals = new ArrayList<>(List.of("1m", "5m", "15m", "1h", "4h", "1d"));
        private String timezone = "UTC";

        public List<String> getIntervals() {
            return intervals;
        }

        public void setIntervals(List<String> intervals) {
            this.intervals = intervals;
        }

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }
    }

    /**
     * 北向 WebSocket 行情推送参数。
     */
    public static class WebSocket {
        private Duration pingInterval = Duration.ofSeconds(30);
        private Duration pongTimeout = Duration.ofSeconds(10);
        private Duration staleScanInterval = Duration.ofSeconds(1);

        public Duration getPingInterval() {
            return pingInterval;
        }

        public void setPingInterval(Duration pingInterval) {
            this.pingInterval = pingInterval;
        }

        public Duration getPongTimeout() {
            return pongTimeout;
        }

        public void setPongTimeout(Duration pongTimeout) {
            this.pongTimeout = pongTimeout;
        }

        public Duration getStaleScanInterval() {
            return staleScanInterval;
        }

        public void setStaleScanInterval(Duration staleScanInterval) {
            this.staleScanInterval = staleScanInterval;
        }
    }
}
