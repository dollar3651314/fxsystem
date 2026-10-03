package com.falconx.gateway;

import tools.jackson.databind.JsonNode;
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
 * Stage 7 代表性 E2E：注册 -> 入金 -> 登录 -> 开仓 -> 手动平仓。
 */
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@E2ECleanupDatabases
class GatewayManualCloseE2ETests extends GatewayTradingRiskE2ETestSupport {

    private static final String IDENTITY_DB_NAME = "fx_id_gw_close_" + shortRandomSuffix(16);
    private static final String TRADING_DB_NAME = "fx_tr_gw_close_" + shortRandomSuffix(16);
    private static final String MARKET_DB_NAME = "fx_mk_gw_close_" + shortRandomSuffix(16);
    private static final String WALLET_DB_NAME = "fx_wa_gw_close_" + shortRandomSuffix(16);
    private static final StartedServiceHolder IDENTITY_SERVICE = newIdentityServiceHolder(
            IDENTITY_DB_NAME,
            "gateway-close-identity-" + randomSuffix()
    );
    private static final StartedServiceHolder TRADING_SERVICE = newTradingServiceHolder(
            TRADING_DB_NAME,
            "gateway-close-trading-" + randomSuffix()
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
    void shouldClosePositionManuallyThroughGatewayWithRealWalletAndMarketRuntime() throws Exception {
        AuthenticatedGatewayUser user = registerDepositActivateAndLogin(
                IDENTITY_SERVICE,
                TRADING_SERVICE,
                WALLET_SERVICE,
                MARKET_SERVICE,
                new BigDecimal("2000.00000000")
        );
        ingestMarketQuote(
                MARKET_SERVICE,
                TRADING_SERVICE,
                E2E_TRADING_SYMBOL,
                new BigDecimal("9990.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"),
                OffsetDateTime.now(),
                "gateway-stage7-manual-open"
        );

        long positionId = placeMarketOrderThroughGateway(
                user.accessToken(),
                "gw-close-order-" + shortRandomSuffix(12),
                new BigDecimal("10100.0"),
                new BigDecimal("9800.0")
        );

        JsonNode accountAfterOrder = waitForGatewayAccountSnapshot(
                user.accessToken(),
                snapshot -> snapshot.path("data").path("openPositions").isArray()
                        && snapshot.path("data").path("openPositions").size() == 1,
                "手动平仓 E2E 下单后 gateway 账户视图未出现 OPEN 持仓"
        );
        BigDecimal balanceAfterOrder = new BigDecimal(accountAfterOrder.path("data").path("balance").asText());

        ingestMarketQuote(
                MARKET_SERVICE,
                TRADING_SERVICE,
                E2E_TRADING_SYMBOL,
                new BigDecimal("10045.00000000"),
                new BigDecimal("10055.00000000"),
                new BigDecimal("10050.00000000"),
                OffsetDateTime.now(),
                "gateway-stage7-manual-close"
        );

        EntityExchangeResult<byte[]> closeResult = webTestClient.post()
                .uri("/api/v1/trading/positions/{positionId}/close", positionId)
                .header("Authorization", "Bearer " + user.accessToken())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Trace-Id")
                .expectBody()
                .returnResult();
        assertHasTraceHeader(closeResult);

        JsonNode closeJson = readJson(closeResult);
        Assertions.assertEquals("0", closeJson.path("code").asText());
        Assertions.assertEquals("CLOSED", closeJson.path("data").path("positionStatus").asText());
        Assertions.assertEquals("MANUAL", closeJson.path("data").path("closeReason").asText());
        Assertions.assertTrue(closeJson.path("data").path("account").path("openPositions").isArray());
        Assertions.assertEquals(0, closeJson.path("data").path("account").path("openPositions").size());

        waitForPositionStatus(TRADING_SERVICE, positionId, 2);
        JsonNode accountAfterClose = waitForGatewayAccountSnapshot(
                user.accessToken(),
                snapshot -> snapshot.path("data").path("openPositions").isArray()
                        && snapshot.path("data").path("openPositions").size() == 0
                        && new BigDecimal(snapshot.path("data").path("marginUsed").asText())
                        .compareTo(BigDecimal.ZERO) == 0,
                "手动平仓后 gateway 账户视图未收敛到空持仓"
        );

        BigDecimal finalBalance = new BigDecimal(accountAfterClose.path("data").path("balance").asText());
        Assertions.assertTrue(finalBalance.compareTo(balanceAfterOrder) > 0);

        Assertions.assertEquals(1L, countRows(
                TRADING_SERVICE.getBean(javax.sql.DataSource.class),
                "SELECT COUNT(1) FROM t_position WHERE id = ? AND status = 2 AND close_reason = 1",
                positionId
        ));
        Assertions.assertEquals(1L, countRows(
                TRADING_SERVICE.getBean(javax.sql.DataSource.class),
                "SELECT COUNT(1) FROM t_trade WHERE position_id = ? AND trade_type = 2",
                positionId
        ));
        Assertions.assertEquals(1L, countRows(
                TRADING_SERVICE.getBean(javax.sql.DataSource.class),
                "SELECT COUNT(1) FROM t_ledger WHERE user_id = ? AND biz_type = 8",
                user.userId()
        ));
        Assertions.assertEquals(0, decimalValue(
                TRADING_SERVICE.getBean(javax.sql.DataSource.class),
                "SELECT net_exposure FROM t_risk_exposure WHERE symbol = ?",
                E2E_TRADING_SYMBOL
        ).compareTo(BigDecimal.ZERO));
        Assertions.assertEquals(1L, countRows(
                TRADING_SERVICE.getBean(javax.sql.DataSource.class),
                "SELECT COUNT(1) FROM t_outbox WHERE event_type = ?",
                "trading.position.closed"
        ));
    }
}
