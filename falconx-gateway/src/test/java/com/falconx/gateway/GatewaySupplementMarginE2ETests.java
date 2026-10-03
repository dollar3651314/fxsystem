package com.falconx.gateway;

import tools.jackson.databind.JsonNode;
import com.falconx.gateway.support.E2ECleanupDatabases;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/**
 * Stage 7A 逐仓 E2E：追加保证金后旧强平价失效，新强平价生效。
 */
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@E2ECleanupDatabases
class GatewaySupplementMarginE2ETests extends GatewayTradingRiskE2ETestSupport {

    private static final String IDENTITY_DB_NAME = "fx_id_gw_margin_" + shortRandomSuffix(16);
    private static final String TRADING_DB_NAME = "fx_tr_gw_margin_" + shortRandomSuffix(16);
    private static final String MARKET_DB_NAME = "fx_mk_gw_margin_" + shortRandomSuffix(16);
    private static final String WALLET_DB_NAME = "fx_wa_gw_margin_" + shortRandomSuffix(16);
    private static final StartedServiceHolder IDENTITY_SERVICE = newIdentityServiceHolder(
            IDENTITY_DB_NAME,
            "gateway-margin-identity-" + randomSuffix()
    );
    private static final StartedServiceHolder TRADING_SERVICE = newTradingServiceHolder(
            TRADING_DB_NAME,
            "gateway-margin-trading-" + randomSuffix()
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
    void shouldKeepPositionOpenAtOldLiquidationPriceAndLiquidateAtRecalculatedPriceThroughGateway()
            throws Exception {
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
                "gateway-stage7a-margin-open"
        );

        long positionId = placeMarketOrderThroughGateway(
                user.accessToken(),
                "gw-margin-order-" + shortRandomSuffix(12),
                new BigDecimal("12000.0"),
                new BigDecimal("8000.0")
        );
        DataSource tradingDataSource = TRADING_SERVICE.getBean(DataSource.class);
        BigDecimal initialLiquidationPrice = decimalValue(
                tradingDataSource,
                "SELECT liquidation_price FROM t_position WHERE id = ?",
                positionId
        );
        Assertions.assertTrue(initialLiquidationPrice.compareTo(BigDecimal.ZERO) > 0);

        JsonNode marginJson = supplementMarginThroughGateway(
                user.accessToken(),
                positionId,
                new BigDecimal("200.0")
        );
        BigDecimal recalculatedLiquidationPrice = decimalValue(
                tradingDataSource,
                "SELECT liquidation_price FROM t_position WHERE id = ?",
                positionId
        );
        Assertions.assertEquals("ISOLATED", marginJson.path("data").path("marginMode").asText());
        Assertions.assertEquals(0, recalculatedLiquidationPrice.compareTo(
                marginJson.path("data").path("liquidationPrice").decimalValue()
        ));
        Assertions.assertNotEquals(0, initialLiquidationPrice.compareTo(recalculatedLiquidationPrice));

        ingestBuyPositionTriggerQuote(initialLiquidationPrice, "gateway-stage7a-margin-old-liq");
        waitForAssertion(() -> {
            Assertions.assertEquals(1L, countRows(
                    tradingDataSource,
                    "SELECT COUNT(1) FROM t_position WHERE id = ? AND status = 1",
                    positionId
            ));
            Assertions.assertEquals(0L, countRows(
                    tradingDataSource,
                    "SELECT COUNT(1) FROM t_liquidation_log WHERE position_id = ?",
                    positionId
            ));
        }, "旧强平价报价不应触发追加保证金后的逐仓持仓强平");

        ingestBuyPositionTriggerQuote(recalculatedLiquidationPrice, "gateway-stage7a-margin-new-liq");
        waitForPositionStatus(TRADING_SERVICE, positionId, 3);
        waitForAssertion(() -> {
            Assertions.assertEquals(1L, countRows(
                    tradingDataSource,
                    "SELECT COUNT(1) FROM t_position WHERE id = ? AND status = 3 AND close_reason = 4",
                    positionId
            ));
            Assertions.assertEquals(1L, countRows(
                    tradingDataSource,
                    "SELECT COUNT(1) FROM t_ledger WHERE user_id = ? AND biz_type = 9",
                    user.userId()
            ));
            Assertions.assertEquals(1L, countRows(
                    tradingDataSource,
                    "SELECT COUNT(1) FROM t_liquidation_log WHERE position_id = ?",
                    positionId
            ));
        }, "新强平价报价未触发逐仓持仓强平闭环");
    }

    private JsonNode supplementMarginThroughGateway(String accessToken,
                                                    long positionId,
                                                    BigDecimal amount) throws Exception {
        EntityExchangeResult<byte[]> marginResult = webTestClient.post()
                .uri("/api/v1/trading/positions/" + positionId + "/margin")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "amount": %s
                        }
                        """.formatted(amount.toPlainString()))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Trace-Id")
                .expectBody()
                .returnResult();
        assertHasTraceHeader(marginResult);

        JsonNode marginJson = readJson(marginResult);
        Assertions.assertEquals("0", marginJson.path("code").asText());
        Assertions.assertEquals(positionId, marginJson.path("data").path("positionId").asLong());
        return marginJson;
    }

    private void ingestBuyPositionTriggerQuote(BigDecimal bid, String source) throws Exception {
        BigDecimal ask = bid.add(new BigDecimal("10.00000000"));
        BigDecimal mark = bid.add(new BigDecimal("5.00000000"));
        ingestMarketQuote(
                MARKET_SERVICE,
                TRADING_SERVICE,
                E2E_TRADING_SYMBOL,
                bid,
                ask,
                mark,
                OffsetDateTime.now(),
                source
        );
    }
}
