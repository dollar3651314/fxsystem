package com.falconx.gateway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.falconx.domain.enums.ChainType;
import com.falconx.identity.IdentityServiceApplication;
import com.falconx.identity.config.IdentityServiceProperties;
import com.falconx.infrastructure.kafka.KafkaEventHeaderConstants;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.market.MarketServiceApplication;
import com.falconx.market.application.MarketDataIngestionApplicationService;
import com.falconx.market.provider.ExternalRawQuote;
import com.falconx.market.service.MarketTradingScheduleWarmupService;
import com.falconx.trading.TradingCoreServiceApplication;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.wallet.WalletServiceApplication;
import com.falconx.wallet.application.WalletDepositTrackingApplicationService;
import com.falconx.wallet.entity.WalletAddressAssignment;
import com.falconx.wallet.entity.WalletAddressStatus;
import com.falconx.wallet.entity.WalletDepositTransaction;
import com.falconx.wallet.listener.ObservedDepositTransaction;
import com.falconx.wallet.producer.WalletOutboxDispatcher;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.http.MediaType;

/**
 * gateway 跨模块 E2E 测试支撑。
 *
 * <p>该类只存在于测试源码中，用来复用 Stage 7 代表性 E2E 所需的正式支撑：
 * 下游应用上下文启动、Kafka 入口驱动、JDBC owner 表断言、账户视图收敛等待与随机 DB 隔离。
 */
abstract class GatewayTradingRiskE2ETestSupport {

    protected static final String TRADING_DEPOSIT_CREDITED_TOPIC = "falconx.trading.deposit.credited";
    protected static final String E2E_TRADING_SYMBOL = "BTCUSD";
    protected static final int IDENTITY_USER_STATUS_ACTIVE = 1;
    protected static final String IDENTITY_CONFIG_LOCATION = moduleResourceLocation(
            "falconx-identity-service",
            "src/main/resources/application.yml"
    );
    protected static final String IDENTITY_STAGE5_CONFIG_LOCATION = moduleResourceLocation(
            "falconx-identity-service",
            "src/test/resources/application-stage5.yml"
    );
    protected static final String MARKET_CONFIG_LOCATION = moduleResourceLocation(
            "falconx-market-service",
            "src/main/resources/application.yml"
    );
    protected static final String TRADING_CONFIG_LOCATION = moduleResourceLocation(
            "falconx-trading-core-service",
            "src/main/resources/application.yml"
    );
    protected static final String WALLET_CONFIG_LOCATION = moduleResourceLocation(
            "falconx-wallet-service",
            "src/main/resources/application.yml"
    );
    protected static final String IDENTITY_FLYWAY_LOCATION = moduleResourceLocation(
            "falconx-identity-service",
            "src/main/resources/db/migration"
    );
    protected static final String MARKET_FLYWAY_LOCATION = moduleResourceLocation(
            "falconx-market-service",
            "src/main/resources/db/migration"
    );
    protected static final String TRADING_FLYWAY_LOCATION = moduleResourceLocation(
            "falconx-trading-core-service",
            "src/main/resources/db/migration"
    );
    protected static final String WALLET_FLYWAY_LOCATION = moduleResourceLocation(
            "falconx-wallet-service",
            "src/main/resources/db/migration"
    );

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    protected ObjectMapper objectMapper;

    protected WebTestClient webTestClient;

    @BeforeEach
    void setUpGatewayWebTestClient() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + gatewayPort)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
    }

    protected static StartedServiceHolder newIdentityServiceHolder(String databaseName, String consumerGroupId) {
        return new StartedServiceHolder(
                IdentityServiceApplication.class,
                "stage5",
                List.of(
                        "spring.main.web-application-type=servlet",
                        "spring.cloud.gateway.enabled=false",
                        "spring.config.location=file:" + IDENTITY_CONFIG_LOCATION,
                        "spring.config.additional-location=file:" + IDENTITY_STAGE5_CONFIG_LOCATION,
                        "spring.flyway.locations=filesystem:" + IDENTITY_FLYWAY_LOCATION,
                        "spring.autoconfigure.exclude=org.springframework.cloud.gateway.config.GatewayAutoConfiguration,"
                                + "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
                                + "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration",
                        "server.port=0",
                        "spring.datasource.url=jdbc:mysql://localhost:3306/" + databaseName
                                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                        "spring.datasource.username=root",
                        "spring.datasource.password=root",
                        "falconx.identity.kafka.consumer-group-id=" + consumerGroupId
                )
        );
    }

    protected static StartedServiceHolder newTradingServiceHolder(String databaseName, String consumerGroupId) {
        return new StartedServiceHolder(
                TradingCoreServiceApplication.class,
                "stage5",
                List.of(
                        "spring.main.web-application-type=servlet",
                        "spring.cloud.gateway.enabled=false",
                        "spring.config.location=file:" + TRADING_CONFIG_LOCATION,
                        "spring.flyway.locations=filesystem:" + TRADING_FLYWAY_LOCATION,
                        "spring.autoconfigure.exclude=org.springframework.cloud.gateway.config.GatewayAutoConfiguration,"
                                + "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
                                + "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration",
                        "server.port=0",
                        "spring.datasource.url=jdbc:mysql://localhost:3306/" + databaseName
                                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                        "spring.datasource.username=root",
                        "spring.datasource.password=root",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=6380",
                        "falconx.trading.kafka.consumer-group-id=" + consumerGroupId
                )
        );
    }

    protected static StartedServiceHolder newMarketServiceHolder(String databaseName) {
        return new StartedServiceHolder(
                MarketServiceApplication.class,
                "stage5",
                List.of(
                        "spring.main.web-application-type=servlet",
                        "spring.cloud.gateway.enabled=false",
                        "spring.config.location=file:" + MARKET_CONFIG_LOCATION,
                        "spring.flyway.locations=filesystem:" + MARKET_FLYWAY_LOCATION,
                        "spring.autoconfigure.exclude=org.springframework.cloud.gateway.config.GatewayAutoConfiguration,"
                                + "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
                                + "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration",
                        "server.port=0",
                        "spring.datasource.url=jdbc:mysql://localhost:3306/" + databaseName
                                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                        "spring.datasource.username=root",
                        "spring.datasource.password=root",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=6380",
                        "falconx.market.tiingo.enabled=false",
                        "falconx.market.tiingo.api-key=",
                        "falconx.market.tiingo.crypto-symbol-import.enabled=false",
                        "falconx.market.redis.quote-ttl=60s",
                        "falconx.market.stale.max-age=30s",
                        "falconx.market.quote-quality.max-tick-jump-rate=0",
                        "falconx.market.quote-quality.max-volatility-rate=0"
                )
        );
    }

    protected static StartedServiceHolder newWalletServiceHolder(String databaseName) {
        return new StartedServiceHolder(
                WalletServiceApplication.class,
                "stage5",
                List.of(
                        "spring.main.web-application-type=servlet",
                        "spring.cloud.gateway.enabled=false",
                        "spring.config.location=file:" + WALLET_CONFIG_LOCATION,
                        "spring.flyway.locations=filesystem:" + WALLET_FLYWAY_LOCATION,
                        "spring.autoconfigure.exclude=org.springframework.cloud.gateway.config.GatewayAutoConfiguration,"
                                + "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
                                + "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration",
                        "server.port=0",
                        "spring.datasource.url=jdbc:mysql://localhost:3306/" + databaseName
                                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                        "spring.datasource.username=root",
                        "spring.datasource.password=root",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=6380",
                        "falconx.wallet.derivation.eth-account-xpub="
                                + GatewayWalletTestXpubSupport.ethAccountXpub(),
                        "falconx.wallet.derivation.tron-account-xpub="
                                + GatewayWalletTestXpubSupport.tronAccountXpub(),
                        "logging.level.com.falconx.wallet.listener.Web3jChainDepositListener=ERROR"
                )
        );
    }

    protected static void registerGatewayRouteProperties(DynamicPropertyRegistry registry,
                                                         StartedServiceHolder identityService,
                                                         StartedServiceHolder tradingService,
                                                         StartedServiceHolder marketService,
                                                         StartedServiceHolder walletService) {
        registry.add("falconx.gateway.routes.identity-base-url", identityService::baseUrl);
        registry.add("falconx.gateway.routes.trading-base-url", tradingService::baseUrl);
        registry.add("falconx.gateway.routes.market-base-url", marketService::baseUrl);
        registry.add("falconx.gateway.routes.wallet-base-url", walletService::baseUrl);
        registry.add("falconx.gateway.security.public-key-pem",
                () -> identityService.getBean(IdentityServiceProperties.class).getKeyPair().getPublicKeyPem());
    }

    protected static void stopStartedServices(StartedServiceHolder walletService,
                                              StartedServiceHolder marketService,
                                              StartedServiceHolder tradingService,
                                              StartedServiceHolder identityService) {
        walletService.close();
        marketService.close();
        tradingService.close();
        identityService.close();
    }

    protected AuthenticatedGatewayUser registerDepositActivateAndLogin(StartedServiceHolder identityService,
                                                                       StartedServiceHolder tradingService,
                                                                       StartedServiceHolder walletService,
                                                                       StartedServiceHolder marketService,
                                                                       BigDecimal depositAmount) throws Exception {
        waitForKafkaListenerAssignment("trading", tradingService.getBean(KafkaListenerEndpointRegistry.class));
        waitForKafkaListenerAssignment("identity", identityService.getBean(KafkaListenerEndpointRegistry.class));
        waitForKafkaListenerAssignment("wallet", walletService.getBean(KafkaListenerEndpointRegistry.class));
        resetTradingRuntimeState(tradingService, marketService);
        clearIdentitySecurityKeys(identityService.getBean(StringRedisTemplate.class));

        String email = "gateway.stage7." + randomSuffix() + "@example.com";
        String password = "Passw0rd!";
        String txHash = "0xgatewaystage7" + randomSuffix();

        EntityExchangeResult<byte[]> registerResult = webTestClient.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "email": "%s",
                          "password": "%s",
                          "firstName": "Gateway",
                          "middleName": "Integration",
                          "lastName": "User",
                          "birthDate": "1990-01-01",
                          "nationality": "USA"
                        }
                        """.formatted(email, password))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(TraceIdConstants.TRACE_ID_HEADER)
                .expectBody()
                .returnResult();
        assertHasTraceHeader(registerResult);

        JsonNode registerJson = readJson(registerResult);
        Assertions.assertEquals("0", registerJson.path("code").asText());
        Assertions.assertEquals("ACTIVE", registerJson.path("data").path("status").asText());
        long userId = registerJson.path("data").path("userId").asLong();
        WalletAddressAssignment walletAddressAssignment = waitForWalletAddressProvision(walletService, userId);

        try (KafkaConsumer<String, String> creditedConsumer = createTopicConsumer(TRADING_DEPOSIT_CREDITED_TOPIC)) {
            WalletDepositTransaction walletDepositTransaction = recordConfirmedWalletDeposit(
                    walletService,
                    walletAddressAssignment,
                    txHash,
                    depositAmount,
                    OffsetDateTime.now().minusMinutes(10)
            );

            waitForAssertion(() -> {
                Assertions.assertEquals(1L, countRows(
                        walletService.getBean(DataSource.class),
                        "SELECT COUNT(1) FROM t_wallet_address WHERE user_id = ? AND chain = ? AND address = ?",
                        userId,
                        ChainType.ETH.name(),
                        walletAddressAssignment.address()
                ));
                Assertions.assertEquals(1L, countRows(
                        walletService.getBean(DataSource.class),
                        "SELECT COUNT(1) FROM t_wallet_deposit_tx WHERE id = ? AND status = 2",
                        walletDepositTransaction.id()
                ));
                Assertions.assertEquals(1L, countRows(
                        walletService.getBean(DataSource.class),
                        "SELECT COUNT(1) FROM t_outbox WHERE event_type = ? AND status = 2",
                        "wallet.deposit.confirmed"
                ));
                Assertions.assertEquals(1L, countRows(
                        tradingService.getBean(DataSource.class),
                        "SELECT COUNT(1) FROM t_deposit WHERE wallet_tx_id = ?",
                        walletDepositTransaction.id()
                ));
                Assertions.assertEquals(1L, countRows(
                        tradingService.getBean(DataSource.class),
                        "SELECT COUNT(1) FROM t_inbox WHERE event_type = ? AND status = 1",
                        "wallet.deposit.confirmed"
                ));
                Assertions.assertEquals(1L, countRows(
                        tradingService.getBean(DataSource.class),
                        "SELECT COUNT(1) FROM t_outbox WHERE event_type = ?",
                        "trading.deposit.credited"
                ));
            }, "wallet.deposit.confirmed 未驱动 trading-core 完成真实入账");

            dispatchTradingOutboxNow(tradingService);
            ConsumerRecord<String, String> creditedRecord = waitForCreditedRecord(creditedConsumer, txHash);
            String creditedEventId = requiredHeader(creditedRecord, KafkaEventHeaderConstants.EVENT_ID_HEADER);

            waitForAssertion(() -> {
                Assertions.assertEquals(1L, countRows(
                        identityService.getBean(DataSource.class),
                        "SELECT COUNT(1) FROM t_inbox WHERE event_id = ? AND status = 1",
                        creditedEventId
                ));
                Assertions.assertEquals(IDENTITY_USER_STATUS_ACTIVE, intValue(
                        identityService.getBean(DataSource.class),
                        "SELECT status FROM t_user WHERE id = ?",
                        userId
                ));
            }, "identity-service 未在真实 Kafka 入口下完成 deposit.credited 幂等入箱");
        }

        EntityExchangeResult<byte[]> loginResult = webTestClient.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "email": "%s",
                          "password": "%s"
                        }
                        """.formatted(email, password))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(TraceIdConstants.TRACE_ID_HEADER)
                .expectBody()
                .returnResult();
        assertHasTraceHeader(loginResult);

        JsonNode loginJson = readJson(loginResult);
        Assertions.assertEquals("0", loginJson.path("code").asText());
        String accessToken = loginJson.path("data").path("accessToken").asText();
        Assertions.assertFalse(accessToken.isBlank());
        return new AuthenticatedGatewayUser(
                userId,
                email,
                password,
                accessToken,
                depositAmount,
                walletAddressAssignment.address(),
                txHash
        );
    }

    protected long placeMarketOrderThroughGateway(String accessToken,
                                                  String clientOrderId,
                                                  BigDecimal takeProfitPrice,
                                                  BigDecimal stopLossPrice) throws Exception {
        EntityExchangeResult<byte[]> marketOrderResult = webTestClient.post()
                .uri("/api/v1/trading/orders/market")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "symbol": "%s",
                          "side": "BUY",
                          "quantity": 1.0,
                          "leverage": 10,
                          "takeProfitPrice": %s,
                          "stopLossPrice": %s,
                          "clientOrderId": "%s"
                        }
                        """.formatted(
                        E2E_TRADING_SYMBOL,
                        takeProfitPrice.toPlainString(),
                        stopLossPrice.toPlainString(),
                        clientOrderId
                ))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(TraceIdConstants.TRACE_ID_HEADER)
                .expectBody()
                .returnResult();
        assertHasTraceHeader(marketOrderResult);

        JsonNode marketOrderJson = readJson(marketOrderResult);
        Assertions.assertEquals("0", marketOrderJson.path("code").asText());
        long positionId = marketOrderJson.path("data").path("positionId").asLong();
        Assertions.assertTrue(positionId > 0);
        return positionId;
    }

    protected JsonNode waitForGatewayAccountSnapshot(String accessToken,
                                                     Predicate<JsonNode> predicate,
                                                     String failureMessage) throws Exception {
        final JsonNode[] holder = new JsonNode[1];
        waitForAssertion(() -> {
            JsonNode snapshot;
            try {
                snapshot = getGatewayAccountSnapshot(accessToken);
            } catch (Exception exception) {
                throw new IllegalStateException("读取 gateway 账户视图失败", exception);
            }
            Assertions.assertEquals("0", snapshot.path("code").asText());
            Assertions.assertTrue(predicate.test(snapshot));
            holder[0] = snapshot;
        }, failureMessage);
        return holder[0];
    }

    protected JsonNode getGatewayAccountSnapshot(String accessToken) throws Exception {
        EntityExchangeResult<byte[]> accountResult = webTestClient.get()
                .uri("/api/v1/trading/accounts/me")
                .header("Authorization", "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(TraceIdConstants.TRACE_ID_HEADER)
                .expectBody()
                .returnResult();
        assertHasTraceHeader(accountResult);
        return readJson(accountResult);
    }

    protected void waitForPositionStatus(StartedServiceHolder tradingService, long positionId, int expectedStatus) throws Exception {
        waitForAssertion(() -> Assertions.assertEquals(expectedStatus, intValue(
                tradingService.getBean(DataSource.class),
                "SELECT status FROM t_position WHERE id = ?",
                positionId
        )), "持仓状态未收敛到 " + expectedStatus + ", positionId=" + positionId);
    }

    /**
     * 重置 trading 侧运行时测试状态，并由 real market-service 重新预热交易时间快照。
     */
    protected void resetTradingRuntimeState(StartedServiceHolder tradingService,
                                            StartedServiceHolder marketService) throws Exception {
        StringRedisTemplate stringRedisTemplate = tradingService.getBean(StringRedisTemplate.class);
        stringRedisTemplate.delete("falconx:trading:quote:snapshot:" + E2E_TRADING_SYMBOL);
        stringRedisTemplate.delete("falconx:market:trading:schedule:" + E2E_TRADING_SYMBOL);
        stringRedisTemplate.delete("falconx:market:price:" + E2E_TRADING_SYMBOL);
        tradingService.getBean(OpenPositionSnapshotStore.class).replaceAll(List.of());

        marketService.getBean(MarketTradingScheduleWarmupService.class).refreshAll();
        waitForAssertion(() -> {
            String payload = marketService.getBean(StringRedisTemplate.class)
                    .opsForValue()
                    .get("falconx:market:trading:schedule:" + E2E_TRADING_SYMBOL);
            Assertions.assertNotNull(payload);
            Assertions.assertFalse(payload.isBlank());
        }, "market-service 交易时间快照未在 Redis 中完成预热");
    }

    /**
     * 通过 real wallet-service owner 应用链路写入 confirmed 入金，并显式触发 wallet outbox。
     */
    protected WalletDepositTransaction recordConfirmedWalletDeposit(StartedServiceHolder walletService,
                                                                   WalletAddressAssignment walletAddressAssignment,
                                                                   String txHash,
                                                                   BigDecimal amount,
                                                                   OffsetDateTime detectedAt) throws Exception {
        WalletDepositTransaction walletDepositTransaction = walletService
                .getBean(WalletDepositTrackingApplicationService.class)
                .trackObservedDeposit(new ObservedDepositTransaction(
                        walletAddressAssignment.chain(),
                        "USDT",
                        null,
                        txHash,
                        0,
                        "0xgatewaystage6afrom",
                        walletAddressAssignment.address(),
                        amount,
                        System.currentTimeMillis(),
                        12,
                        detectedAt,
                        false
                ));

        Assertions.assertNotNull(walletDepositTransaction.id());
        Assertions.assertEquals(walletAddressAssignment.userId(), walletDepositTransaction.userId());
        Assertions.assertEquals(walletAddressAssignment.address(), walletDepositTransaction.toAddress());
        Assertions.assertEquals(txHash, walletDepositTransaction.txHash());

        waitForAssertion(() -> Assertions.assertEquals(1L, countRows(
                walletService.getBean(DataSource.class),
                "SELECT COUNT(1) FROM t_wallet_deposit_tx WHERE id = ? AND status = 2",
                walletDepositTransaction.id()
        )), "wallet-service 未按 owner 链路写入 confirmed 入金事实");

        walletService.getBean(WalletOutboxDispatcher.class).dispatchPendingMessages();
        waitForAssertion(() -> Assertions.assertEquals(1L, countRows(
                walletService.getBean(DataSource.class),
                "SELECT COUNT(1) FROM t_outbox WHERE event_type = ? AND status = 2",
                "wallet.deposit.confirmed"
        )), "wallet-service outbox 未完成 confirmed 事件发送");
        return walletDepositTransaction;
    }

    protected WalletAddressAssignment waitForWalletAddressProvision(StartedServiceHolder walletService,
                                                                    long userId) throws Exception {
        WalletAddressAssignment[] holder = new WalletAddressAssignment[1];
        waitForAssertion(() -> {
            holder[0] = findWalletAddress(walletService.getBean(DataSource.class), userId, ChainType.ETH);
            Assertions.assertNotNull(holder[0]);
        }, "wallet-service 未通过 user.registered Kafka 事件为用户完成 ETH 地址分配");
        return holder[0];
    }

    protected WalletAddressAssignment findWalletAddress(DataSource dataSource,
                                                        long userId,
                                                        ChainType chain) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, user_id, chain, token, network, address, address_index,
                            derivation_path, status, assigned_at
                     FROM t_wallet_address
                     WHERE user_id = ?
                       AND chain = ?
                     """)) {
            statement.setLong(1, userId);
            statement.setString(2, chain.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                Timestamp assignedAt = resultSet.getTimestamp("assigned_at");
                WalletAddressStatus[] statuses = WalletAddressStatus.values();
                return new WalletAddressAssignment(
                        resultSet.getLong("id"),
                        resultSet.getLong("user_id"),
                        ChainType.valueOf(resultSet.getString("chain")),
                        resultSet.getString("token"),
                        resultSet.getString("network"),
                        resultSet.getString("address"),
                        resultSet.getInt("address_index"),
                        resultSet.getString("derivation_path"),
                        statuses[resultSet.getInt("status")],
                        assignedAt == null ? null : assignedAt.toInstant().atOffset(ZoneOffset.UTC)
                );
            }
        } catch (Exception exception) {
            throw new IllegalStateException("读取 wallet 地址失败 userId=" + userId + ", chain=" + chain, exception);
        }
    }

    /**
     * 通过 real market-service owner 应用链路写入报价，并等待 market -> trading 真实事件链生效。
     */
    protected void ingestMarketQuote(StartedServiceHolder marketService,
                                     StartedServiceHolder tradingService,
                                     String symbol,
                                     BigDecimal bid,
                                     BigDecimal ask,
                                     BigDecimal last,
                                     OffsetDateTime quoteTime,
                                     String source) throws Exception {
        marketService.getBean(MarketDataIngestionApplicationService.class).ingest(new ExternalRawQuote(
                symbol,
                bid,
                ask,
                quoteTime,
                source
        ));

        waitForAssertion(() -> {
            String marketBid = String.valueOf(marketService.getBean(StringRedisTemplate.class)
                    .opsForHash()
                    .get("falconx:market:price:" + symbol, "bid"));
            String marketAsk = String.valueOf(marketService.getBean(StringRedisTemplate.class)
                    .opsForHash()
                    .get("falconx:market:price:" + symbol, "ask"));
            Assertions.assertEquals(0, bid.compareTo(new BigDecimal(marketBid)));
            Assertions.assertEquals(0, ask.compareTo(new BigDecimal(marketAsk)));
        }, "market-service Redis 最新报价未按 owner 路径写入");

        waitForAssertion(() -> {
            Object tradingMark = tradingService.getBean(StringRedisTemplate.class)
                    .opsForHash()
                    .get("falconx:trading:quote:snapshot:" + symbol, "mark");
            Assertions.assertNotNull(tradingMark);
            Assertions.assertEquals(0, last.compareTo(new BigDecimal(String.valueOf(tradingMark))));
        }, "market -> trading 的真实 price.tick 链路未让 trading Redis 报价快照生效");
    }

    protected void dispatchTradingOutboxNow(StartedServiceHolder tradingService) {
        tradingService.getBean(com.falconx.trading.producer.TradingOutboxDispatcher.class).dispatchPendingMessages();
    }

    protected void waitForKafkaListenerAssignment(String label, KafkaListenerEndpointRegistry registry) throws Exception {
        waitForAssertion(() -> {
            Assertions.assertFalse(registry.getListenerContainers().isEmpty());
            boolean ready = registry.getListenerContainers().stream().allMatch(this::hasAssignments);
            Assertions.assertTrue(ready);
        }, label + " Kafka listener 未在超时内完成分区分配");
    }

    protected boolean hasAssignments(MessageListenerContainer container) {
        return container.isRunning()
                && container.getAssignedPartitions() != null
                && !container.getAssignedPartitions().isEmpty();
    }

    protected KafkaConsumer<String, String> createTopicConsumer(String topic) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "gateway-stage7-observer-" + randomSuffix());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
        List<TopicPartition> partitions = awaitTopicPartitions(consumer, topic);
        consumer.assign(partitions);
        Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
        endOffsets.forEach(consumer::seek);
        return consumer;
    }

    protected List<TopicPartition> awaitTopicPartitions(KafkaConsumer<String, String> consumer, String topic) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic, Duration.ofMillis(500));
            if (partitionInfos != null && !partitionInfos.isEmpty()) {
                return partitionInfos.stream()
                        .map(partitionInfo -> new TopicPartition(partitionInfo.topic(), partitionInfo.partition()))
                        .toList();
            }
        }
        Assertions.fail("测试消费者未能在超时内加载 topic=" + topic + " 的分区信息");
        return List.of();
    }

    protected ConsumerRecord<String, String> waitForCreditedRecord(KafkaConsumer<String, String> consumer, String txHash)
            throws Exception {
        long deadline = System.currentTimeMillis() + 12_000L;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (record.value() != null && record.value().contains(txHash)) {
                    return record;
                }
            }
        }
        throw new AssertionError("未收到 txHash=" + txHash + " 对应的 trading.deposit.credited Kafka 消息");
    }

    protected String requiredHeader(ConsumerRecord<String, String> record, String headerName) {
        Header header = record.headers().lastHeader(headerName);
        Assertions.assertNotNull(header, "Kafka 消息头缺少 " + headerName);
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    protected void waitForAssertion(Runnable assertion, String failureMessage) throws Exception {
        AssertionError lastError = null;
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                assertion.run();
                return;
            } catch (AssertionError error) {
                lastError = error;
                Thread.sleep(250L);
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new AssertionError(failureMessage);
    }

    protected JsonNode readJson(EntityExchangeResult<byte[]> result) throws Exception {
        return objectMapper.readTree(Objects.requireNonNullElse(result.getResponseBodyContent(), new byte[0]));
    }

    protected void assertHasTraceHeader(EntityExchangeResult<byte[]> result) {
        String traceId = result.getResponseHeaders().getFirst(TraceIdConstants.TRACE_ID_HEADER);
        Assertions.assertNotNull(traceId);
        Assertions.assertFalse(traceId.isBlank());
    }

    protected long countRows(DataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                Assertions.assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("执行计数 SQL 失败: " + sql, exception);
        }
    }

    protected BigDecimal decimalValue(DataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                Assertions.assertTrue(resultSet.next());
                BigDecimal value = resultSet.getBigDecimal(1);
                Assertions.assertNotNull(value);
                return value;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("执行数值 SQL 失败: " + sql, exception);
        }
    }

    protected int intValue(DataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                Assertions.assertTrue(resultSet.next());
                return resultSet.getInt(1);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("执行整型 SQL 失败: " + sql, exception);
        }
    }

    protected long longValue(DataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                Assertions.assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("执行长整型 SQL 失败: " + sql, exception);
        }
    }

    protected void bindParameters(PreparedStatement statement, Object... parameters) throws Exception {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    private void clearIdentitySecurityKeys(StringRedisTemplate stringRedisTemplate) {
        deleteKeysByPattern(stringRedisTemplate, "falconx:auth:login:fail:*");
        deleteKeysByPattern(stringRedisTemplate, "falconx:auth:register:limit:*");
        deleteKeysByPattern(stringRedisTemplate, "falconx:auth:token:blacklist:*");
    }

    private void deleteKeysByPattern(StringRedisTemplate stringRedisTemplate, String pattern) {
        var keys = stringRedisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    protected static String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    protected static String shortRandomSuffix(int maxLength) {
        String suffix = randomSuffix();
        return suffix.substring(0, Math.min(maxLength, suffix.length()));
    }

    protected static String moduleResourceLocation(String moduleName, String relativePath) {
        Path userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                userDir.resolve(moduleName).resolve(relativePath),
                userDir.resolve("..").resolve(moduleName).resolve(relativePath)
        );
        return candidates.stream()
                .map(Path::normalize)
                .filter(Files::exists)
                .map(Path::toAbsolutePath)
                .findFirst()
                .map(Path::toString)
                .orElseThrow(() -> new IllegalStateException("未找到模块资源: " + moduleName + " -> " + relativePath));
    }

    protected record AuthenticatedGatewayUser(long userId,
                                              String email,
                                              String password,
                                              String accessToken,
                                              BigDecimal depositAmount,
                                              String walletAddress,
                                              String txHash) {
    }

    /**
     * 供 gateway E2E 测试复用的下游真实应用上下文持有器。
     */
    protected static final class StartedServiceHolder {

        private final Class<?> applicationClass;
        private final String profile;
        private final List<String> properties;
        private ConfigurableApplicationContext context;
        private int port;

        private StartedServiceHolder(Class<?> applicationClass, String profile, List<String> properties) {
            this.applicationClass = applicationClass;
            this.profile = profile;
            this.properties = properties;
        }

        synchronized String baseUrl() {
            startIfNecessary();
            return "http://localhost:" + port;
        }

        synchronized <T> T getBean(Class<T> beanType) {
            startIfNecessary();
            return context.getBean(beanType);
        }

        synchronized void close() {
            if (context != null) {
                context.close();
                context = null;
            }
        }

        private void startIfNecessary() {
            if (context != null) {
                return;
            }
            String previousWebApplicationType = System.getProperty("spring.main.web-application-type");
            String previousAutoConfigurationExclude = System.getProperty("spring.autoconfigure.exclude");
            try {
                System.setProperty("spring.main.web-application-type", "servlet");
                System.clearProperty("spring.autoconfigure.exclude");
                context = new SpringApplicationBuilder(applicationClass)
                        .profiles(profile)
                        .run(properties.stream()
                                .map(property -> "--" + property)
                                .toArray(String[]::new));
            } finally {
                if (previousWebApplicationType == null) {
                    System.clearProperty("spring.main.web-application-type");
                } else {
                    System.setProperty("spring.main.web-application-type", previousWebApplicationType);
                }
                if (previousAutoConfigurationExclude == null) {
                    System.clearProperty("spring.autoconfigure.exclude");
                } else {
                    System.setProperty("spring.autoconfigure.exclude", previousAutoConfigurationExclude);
                }
            }
            Integer localPort = context.getEnvironment().getProperty("local.server.port", Integer.class);
            Assertions.assertNotNull(localPort, "下游应用上下文未暴露 local.server.port");
            port = localPort;
        }
    }
}
