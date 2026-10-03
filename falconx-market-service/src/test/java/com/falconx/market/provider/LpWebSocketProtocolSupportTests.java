package com.falconx.market.provider;

import com.falconx.market.config.MarketServiceProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.xerial.snappy.Snappy;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 自建 LP Socket.IO 协议处理测试。
 */
class LpWebSocketProtocolSupportTests {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final LpWebSocketProtocolSupport protocolSupport = new LpWebSocketProtocolSupport(objectMapper);

    @Test
    void shouldBuildSocketUriWithAppTokenEncryptedHandshake() {
        MarketServiceProperties.Lp lp = lpProperties();
        byte[] randomBytes = "1234567890abcdef".getBytes(StandardCharsets.UTF_8);

        URI uri = protocolSupport.buildSocketUri(
                lp,
                Instant.ofEpochMilli(1753499149317L),
                "lQ0tVhOR",
                randomBytes
        );

        Assertions.assertEquals("wss", uri.getScheme());
        Assertions.assertEquals("/safe/socket.io/", uri.getPath());
        Map<String, String> query = queryMap(uri);
        Assertions.assertEquals("APPID12345", query.get("APP-ID"));
        Assertions.assertEquals("4", query.get("EIO"));
        Assertions.assertEquals("1", query.get("socketSource"));
        Assertions.assertEquals("1.1", query.get("socketVersion"));
        Assertions.assertEquals("lQ0tVhOR", query.get("nonce"));
        Assertions.assertEquals("1753499149317", query.get("timestamp"));
        Assertions.assertNotEquals(lp.getToken(), query.get("encrypt"));
        Assertions.assertEquals(40, query.get("signature").length());
        Assertions.assertEquals(
                LpWebSocketProtocolSupport.sha1Sorted(
                        lp.getToken(),
                        query.get("timestamp"),
                        query.get("nonce"),
                        query.get("encrypt")
                ),
                query.get("signature")
        );
        Assertions.assertFalse(query.containsKey("Authorization"));
    }

    @Test
    void shouldBuildExternalSubscribePayloadWithoutServerIdWhenNotProvided() {
        String payload = protocolSupport.buildSubscribePayload(null, List.of(" EURUSD ", "XAUUSD", "AAPL.NAS"));

        Assertions.assertFalse(payload.contains("\"serverId\""));
        Assertions.assertTrue(payload.contains("\"symbolList\":[\"EURUSD\",\"XAUUSD\",\"AAPL.NAS\"]"));
    }

    @Test
    void shouldBuildExternalSubscribePayloadWithExplicitServerIdWhenRequested() {
        String payload = protocolSupport.buildSubscribePayload(17, List.of("EURUSD"));

        Assertions.assertTrue(payload.contains("\"serverId\":17"));
        Assertions.assertTrue(payload.contains("\"symbolList\":[\"EURUSD\"]"));
    }

    @Test
    void shouldDecompressFrameAfterSocketIoBinaryHeader() throws Exception {
        String quoteJson = """
                [{"symbol":"EURUSD","bid":"1.08000","ask":"1.08010","ts":"2026-04-29T10:15:30Z"}]
                """;
        byte[] compressed = Snappy.compress(quoteJson, StandardCharsets.UTF_8);
        byte[] socketIoBinaryFrame = new byte[compressed.length + 1];
        socketIoBinaryFrame[0] = 0x04;
        System.arraycopy(compressed, 0, socketIoBinaryFrame, 1, compressed.length);

        String decompressed = protocolSupport.decompressPriceFrame(socketIoBinaryFrame);

        Assertions.assertEquals(quoteJson.strip(), decompressed.strip());
    }

    @Test
    void shouldParseFlexibleLpQuoteJsonIntoExternalRawQuote() {
        String quoteJson = """
                {
                  "data": [
                    {"symbol":"EURUSD","bid":"1.08000","ask":"1.08010","ts":"2026-04-29T10:15:30Z"},
                    {"Symbol":"AAPL.NAS","Bid":2320.10,"Ask":2320.40,"Time":1777457730000}
                  ]
                }
                """;

        List<ExternalRawQuote> quotes = protocolSupport.parseQuotes(quoteJson);

        Assertions.assertEquals(2, quotes.size());
        Assertions.assertEquals("EURUSD", quotes.getFirst().ticker());
        Assertions.assertEquals(new BigDecimal("1.08000"), quotes.getFirst().bid());
        Assertions.assertEquals(new BigDecimal("1.08010"), quotes.getFirst().ask());
        Assertions.assertEquals("TM_QUOTE", quotes.getFirst().source());
        Assertions.assertEquals("AAPL.NAS", quotes.get(1).ticker());
        Assertions.assertEquals(OffsetDateTime.parse("2026-04-29T10:15:30Z"), quotes.get(1).ts());
    }

    @Test
    void shouldParseUnicornSocketDatetimeFieldsIntoExternalRawQuote() {
        String quoteJson = """
                {
                  "symbol": "NZDUSD",
                  "ask": 0.56184,
                  "bid": 0.5618,
                  "datetime": 1744129032,
                  "serverId": "20",
                  "receivingTime": 1744118232,
                  "datetimeUtc": 1744118232
                }
                """;

        List<ExternalRawQuote> quotes = protocolSupport.parseQuotes(quoteJson);

        Assertions.assertEquals(1, quotes.size());
        Assertions.assertEquals("NZDUSD", quotes.getFirst().ticker());
        Assertions.assertEquals(new BigDecimal("0.5618"), quotes.getFirst().bid());
        Assertions.assertEquals(new BigDecimal("0.56184"), quotes.getFirst().ask());
        Assertions.assertEquals(OffsetDateTime.parse("2025-04-08T13:17:12Z"), quotes.getFirst().ts());
    }

    @Test
    void shouldPreferLpReceivingTimeWhenPresent() {
        String quoteJson = """
                {
                  "symbol": "EURUSD",
                  "ask": 1.0801,
                  "bid": 1.0800,
                  "datetimeUtc": 1777539600,
                  "receivingTime": 1777540800
                }
                """;

        List<ExternalRawQuote> quotes = protocolSupport.parseQuotes(quoteJson);

        Assertions.assertEquals(1, quotes.size());
        Assertions.assertEquals(OffsetDateTime.parse("2026-04-30T09:20:00Z"), quotes.getFirst().ts());
    }

    @Test
    void shouldIgnoreQuoteWithoutBidOrAsk() {
        List<ExternalRawQuote> quotes = protocolSupport.parseQuotes("""
                {"data":[{"symbol":"EURUSD","price":"1.08000","ts":"2026-04-29T10:15:30Z"}]}
                """);

        Assertions.assertTrue(quotes.isEmpty());
    }

    @Test
    void shouldAcceptByteBufferPricePayload() throws Exception {
        byte[] compressed = Snappy.compress("""
                {"symbol":"EURUSD","bid":"1.08000","ask":"1.08010","ts":"2026-04-29T10:15:30Z"}
                """, StandardCharsets.UTF_8);

        String decompressed = protocolSupport.decompressPriceFrame(ByteBuffer.wrap(compressed));

        Assertions.assertTrue(decompressed.contains("\"symbol\":\"EURUSD\""));
    }

    private static MarketServiceProperties.Lp lpProperties() {
        MarketServiceProperties.Lp lp = new MarketServiceProperties.Lp();
        lp.setDomain("lp.example.test");
        lp.setAppId("APPID12345");
        lp.setToken("ow-token");
        lp.setSecretKey("U7lFsM0Oo4lsbuQRBXoeuIf6vY7LU0FlmRD31zehov0");
        lp.setServerId(17);
        lp.setMetaTradeVersion(4);
        lp.setMetaTraderId(17);
        return lp;
    }

    private static Map<String, String> queryMap(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(entry -> entry.split("=", 2))
                .collect(Collectors.toMap(
                        entry -> decode(entry[0]),
                        entry -> entry.length == 1 ? "" : decode(entry[1])
                ));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
