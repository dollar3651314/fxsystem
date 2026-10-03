package com.falconx.gateway;

import tools.jackson.databind.JsonNode;
import com.falconx.domain.enums.ChainType;
import com.falconx.gateway.support.E2ECleanupDatabases;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/**
 * `TC-E2E-001` 最小主链路 E2E：真实 wallet/market 运行时参测。
 */
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@E2ECleanupDatabases
class GatewayMinimalMainlineE2ETests extends GatewayTradingRiskE2ETestSupport {

    private static final String IDENTITY_DB_NAME = "fx_id_gw_main_" + shortRandomSuffix(16);
    private static final String TRADING_DB_NAME = "fx_tr_gw_main_" + shortRandomSuffix(16);
    private static final String MARKET_DB_NAME = "fx_mk_gw_main_" + shortRandomSuffix(16);
    private static final String WALLET_DB_NAME = "fx_wa_gw_main_" + shortRandomSuffix(16);

    private static final StartedServiceHolder IDENTITY_SERVICE = newIdentityServiceHolder(
            IDENTITY_DB_NAME,
            "gateway-mainline-identity-" + randomSuffix()
    );
    private static final StartedServiceHolder TRADING_SERVICE = newTradingServiceHolder(
            TRADING_DB_NAME,
            "gateway-mainline-trading-" + randomSuffix()
    );
    private static final StartedServiceHolder MARKET_SERVICE = newMarketServiceHolder(MARKET_DB_NAME);
    private static final StartedServiceHolder WALLET_SERVICE = newWalletServiceHolder(WALLET_DB_NAME);

    @DynamicPropertySource
    static void registerGatewayProperties(DynamicPropertyRegistry registry) {
        registerGatewayRouteProperties(registry, IDENTITY_SERVICE, TRADING_SERVICE, MARKET_SERVICE, WALLET_SERVICE);
    }

    @AfterAll
    static void stopServices() {
        stopStartedServices(WALLET_SERVICE, MARKET_SERVICE, TRADING_SERVICE, IDENTITY_SERVICE);
    }

    @Test
    void shouldCompleteRegisterDepositActivateLoginAndOpenPositionThroughGatewayWithRealWalletAndMarketRuntime()
            throws Exception {
        BigDecimal depositAmount = new BigDecimal("1500.00000000");
        AuthenticatedGatewayUser user = registerDepositActivateAndLogin(
                IDENTITY_SERVICE,
                TRADING_SERVICE,
                WALLET_SERVICE,
                MARKET_SERVICE,
                depositAmount
        );

        long walletTxId = longValue(
                WALLET_SERVICE.getBean(javax.sql.DataSource.class),
                "SELECT id FROM t_wallet_deposit_tx WHERE tx_hash = ?",
                user.txHash()
        );
        Assertions.assertEquals(1L, countRows(
                WALLET_SERVICE.getBean(javax.sql.DataSource.class),
                "SELECT COUNT(1) FROM t_wallet_address WHERE user_id = ? AND chain = ? AND address = ?",
                user.userId(),
                ChainType.ETH.name(),
                user.walletAddress()
        ));
        Assertions.assertEquals(1L, countRows(
                WALLET_SERVICE.getBean(javax.sql.DataSource.class),
                "SELECT COUNT(1) FROM t_wallet_deposit_tx WHERE id = ? AND status = 2",
                walletTxId
        ));
        Assertions.assertEquals(1L, countRows(
                WALLET_SERVICE.getBean(javax.sql.DataSource.class),
                "SELECT COUNT(1) FROM t_outbox WHERE event_type = ? AND status = 2",
                "wallet.deposit.confirmed"
        ));
        Assertions.assertEquals(1L, countRows(
                TRADING_SERVICE.getBean(javax.sql.DataSource.class),
                "SELECT COUNT(1) FROM t_inbox WHERE event_type = ? AND status = 1",
                "wallet.deposit.confirmed"
        ));
        Assertions.assertEquals(1L, countRows(
                TRADING_SERVICE.getBean(javax.sql.DataSource.class),
                "SELECT COUNT(1) FROM t_deposit WHERE wallet_tx_id = ?",
                walletTxId
        ));
        Assertions.assertEquals(1L, countRows(
                TRADING_SERVICE.getBean(javax.sql.DataSource.class),
                "SELECT COUNT(1) FROM t_outbox WHERE event_type = ?",
                "trading.deposit.credited"
        ));
        Assertions.assertEquals(IDENTITY_USER_STATUS_ACTIVE, intValue(
                IDENTITY_SERVICE.getBean(javax.sql.DataSource.class),
                "SELECT status FROM t_user WHERE id = ?",
                user.userId()
        ));

        ingestMarketQuote(
                MARKET_SERVICE,
                TRADING_SERVICE,
                E2E_TRADING_SYMBOL,
                new BigDecimal("9990.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"),
                OffsetDateTime.now(),
                "gateway-stage6a-mainline"
        );

        EntityExchangeResult<byte[]> marketQuoteResult = webTestClient.get()
                .uri("/api/v1/market/quotes/" + E2E_TRADING_SYMBOL)
                .header("Authorization", "Bearer " + user.accessToken())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Trace-Id")
                .expectBody()
                .returnResult();
        assertHasTraceHeader(marketQuoteResult);
        JsonNode marketQuoteJson = readJson(marketQuoteResult);
        Assertions.assertEquals("0", marketQuoteJson.path("code").asText());
        Assertions.assertEquals(0, new BigDecimal("9990.00000000").compareTo(
                marketQuoteJson.path("data").path("bid").decimalValue()
        ));
        Assertions.assertEquals(0, new BigDecimal("10000.00000000").compareTo(
                marketQuoteJson.path("data").path("ask").decimalValue()
        ));
        Assertions.assertEquals(0, new BigDecimal("9995.00000000").compareTo(
                marketQuoteJson.path("data").path("mark").decimalValue()
        ));

        JsonNode accountBeforeOrder = getGatewayAccountSnapshot(user.accessToken());
        Assertions.assertEquals("0", accountBeforeOrder.path("code").asText());
        Assertions.assertEquals(0, depositAmount.compareTo(new BigDecimal(
                accountBeforeOrder.path("data").path("balance").asText()
        )));

        long positionId = placeMarketOrderThroughGateway(
                user.accessToken(),
                "gw-mainline-order-" + shortRandomSuffix(12),
                new BigDecimal("10100.0"),
                new BigDecimal("9800.0")
        );

        JsonNode accountAfterOrder = waitForGatewayAccountSnapshot(
                user.accessToken(),
                snapshot -> snapshot.path("data").path("openPositions").isArray()
                        && snapshot.path("data").path("openPositions").size() == 1,
                "最小主链路开仓后 gateway 账户视图未出现 OPEN 持仓"
        );
        Assertions.assertEquals(positionId, accountAfterOrder.path("data").path("openPositions").get(0).path("positionId").asLong());
    }
}
