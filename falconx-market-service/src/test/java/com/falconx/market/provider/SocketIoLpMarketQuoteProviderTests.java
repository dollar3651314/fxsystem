package com.falconx.market.provider;

import com.falconx.market.config.MarketServiceProperties;
import io.socket.engineio.client.EngineIOException;
import java.math.BigDecimal;
import java.net.ProtocolException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 自建 LP Socket.IO Provider 测试。
 */
class SocketIoLpMarketQuoteProviderTests {

    @Test
    void shouldDescribeConnectErrorWithSanitizedCause() {
        EngineIOException error = new EngineIOException(
                "websocket error",
                new ProtocolException("Expected HTTP 101 response but was '401 Unauthorized' url=wss://lp/socket.io/?APP-ID=app&Authorization=bearer-token")
        );

        String description = SocketIoLpMarketQuoteProvider.describeConnectError(error);

        Assertions.assertTrue(description.contains("EngineIOException: websocket error"));
        Assertions.assertTrue(description.contains("ProtocolException: Expected HTTP 101 response"));
        Assertions.assertFalse(description.contains("APP-ID=app"));
        Assertions.assertFalse(description.contains("Authorization=bearer-token"));
    }

    @Test
    void shouldBuildSocketIoConnectionTargetWithSafeSocketPath() {
        URI fullUri = URI.create("wss://lp.example.test/safe/socket.io/?APP-ID=app&signature=sig&encrypt=enc&EIO=4&socketSource=1&nonce=n&timestamp=1");

        SocketIoLpMarketQuoteProvider.SocketIoConnectionTarget target =
                SocketIoLpMarketQuoteProvider.toSocketIoConnectionTarget(fullUri);

        Assertions.assertEquals(URI.create("wss://lp.example.test/"), target.clientUri());
        Assertions.assertEquals("/safe/socket.io/", target.enginePath());
        Assertions.assertEquals("APP-ID=app&signature=sig&encrypt=enc&EIO=4&socketSource=1&nonce=n&timestamp=1", target.query());
    }

    @Test
    void shouldEmitExternalSubscribePayloadAsRawJsonString() {
        String payload = "{\"serverId\":17,\"symbolList\":[\"EURUSD\"]}";

        Object eventPayload = SocketIoLpMarketQuoteProvider.toSubscribeEventPayload(payload);

        Assertions.assertInstanceOf(String.class, eventPayload);
        Assertions.assertEquals(payload, eventPayload);
    }

    @Test
    void shouldKeepDottedLpSymbolWhenFilteringSubscribedQuotes() {
        ExternalRawQuote quote = new ExternalRawQuote(
                "AAPL.NAS",
                new BigDecimal("190.10"),
                new BigDecimal("190.20"),
                OffsetDateTime.parse("2026-04-30T02:00:00Z"),
                "TM_QUOTE"
        );

        SocketIoLpMarketQuoteProvider.DispatchSummary summary =
                SocketIoLpMarketQuoteProvider.summarizeDispatch(List.of(quote), Set.of("AAPL.NAS"));

        Assertions.assertEquals(1, summary.acceptedQuotes().size());
        Assertions.assertEquals(0, summary.filteredCount());
        Assertions.assertEquals("AAPL.NAS", summary.acceptedQuotes().getFirst().ticker());
    }

    @Test
    void shouldStampQuoteWithPlatformDispatchTime() {
        ExternalRawQuote quote = new ExternalRawQuote(
                "GBPUSD",
                new BigDecimal("1.3474"),
                new BigDecimal("1.3475"),
                OffsetDateTime.parse("2026-04-30T09:40:00Z"),
                "TM_QUOTE"
        );
        OffsetDateTime dispatchTime = OffsetDateTime.parse("2026-04-30T09:50:30Z");

        ExternalRawQuote stamped = SocketIoLpMarketQuoteProvider.withPlatformTimestamp(quote, dispatchTime);

        Assertions.assertEquals("GBPUSD", stamped.ticker());
        Assertions.assertEquals(quote.bid(), stamped.bid());
        Assertions.assertEquals(quote.ask(), stamped.ask());
        Assertions.assertEquals(dispatchTime, stamped.ts());
        Assertions.assertEquals("TM_QUOTE", stamped.source());
    }

    @Test
    void shouldRequireAppTokenEncryptedConnectionFields() {
        MarketServiceProperties.Lp lp = new MarketServiceProperties.Lp();
        lp.setDomain("wss://lp.example.test");
        lp.setAppId("APPID12345");
        lp.setToken("ow-token");
        lp.setSecretKey("U7lFsM0Oo4lsbuQRBXoeuIf6vY7LU0FlmRD31zehov0");
        lp.setServerId(17);

        Assertions.assertTrue(SocketIoLpMarketQuoteProvider.hasRequiredSocketConnectionConfig(lp));
    }

    @Test
    void shouldRequireExplicitServerIdForConnection() {
        MarketServiceProperties.Lp lp = new MarketServiceProperties.Lp();
        lp.setDomain("wss://lp.example.test");
        lp.setAppId("APPID12345");
        lp.setToken("ow-token");
        lp.setSecretKey("U7lFsM0Oo4lsbuQRBXoeuIf6vY7LU0FlmRD31zehov0");

        Assertions.assertFalse(SocketIoLpMarketQuoteProvider.hasRequiredSocketConnectionConfig(lp));
    }
}
