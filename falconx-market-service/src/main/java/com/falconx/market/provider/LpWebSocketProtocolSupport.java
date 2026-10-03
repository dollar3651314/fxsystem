package com.falconx.market.provider;

import com.falconx.market.config.MarketServiceProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.xerial.snappy.Snappy;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 自建 LP Socket.IO 协议支持组件。
 */
@Component
public class LpWebSocketProtocolSupport {

    static final String SOURCE = "TM_QUOTE";

    private static final byte SOCKET_IO_BINARY_HEADER = 0x04;
    private static final List<String> SYMBOL_FIELDS = List.of("symbol", "ticker", "instrument", "Symbol", "Ticker");
    private static final List<String> BID_FIELDS = List.of("bid", "bidPrice", "Bid", "BidPrice");
    private static final List<String> ASK_FIELDS = List.of("ask", "askPrice", "Ask", "AskPrice");
    private static final List<String> TIMESTAMP_FIELDS = List.of(
            "receivingTime",
            "datetimeUtc",
            "datetime",
            "ts",
            "timestamp",
            "time",
            "quoteTimestamp",
            "ctm",
            "ctmUtc",
            "Time"
    );
    private static final int NONCE_LENGTH = 8;
    private static final int AES_RANDOM_BYTES = 16;
    private static final int AES_KEY_BYTES = 32;
    private static final int AES_IV_BYTES = 16;

    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public LpWebSocketProtocolSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 构造 Socket.IO 连接地址。
     *
     * @param lp LP 配置
     * @return 完整连接地址
     */
    public URI buildSocketUri(MarketServiceProperties.Lp lp) {
        byte[] randomBytes = new byte[AES_RANDOM_BYTES];
        secureRandom.nextBytes(randomBytes);
        return buildSocketUri(lp, Instant.now(), randomNonce(), randomBytes);
    }

    URI buildSocketUri(MarketServiceProperties.Lp lp, Instant now, String nonce, byte[] randomBytes) {
        String timestamp = String.valueOf(now.toEpochMilli());
        String encrypt = encrypt(lp.getToken(), lp.getAppId(), lp.getSecretKey(), randomBytes);
        String signature = sha1Sorted(lp.getToken(), timestamp, nonce, encrypt);
        String query = "APP-ID=%s&signature=%s&encrypt=%s&EIO=4&socketSource=1&socketVersion=1.1&nonce=%s&timestamp=%s"
                .formatted(
                        encode(lp.getAppId()),
                        encode(signature),
                        encode(encrypt),
                        encode(nonce),
                        encode(timestamp)
                );
        return URI.create(normalizeSocketBaseUrl(lp.getDomain(), lp.getSocketPath()) + "?" + query);
    }

    /**
     * 构造 LP symbol 订阅 payload。
     *
     * @param serverId LP server id；运行时必须传正数，null 或非正数仅保留为协议兼容路径
     * @param symbols owner 已启用 symbol
     * @return JSON payload
     */
    public String buildSubscribePayload(Integer serverId, List<String> symbols) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            if (serverId != null && serverId > 0) {
                payload.put("serverId", serverId);
            }
            payload.put("symbolList", normalizeSymbols(symbols));
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize LP subscribe payload", exception);
        }
    }

    /**
     * 解压 LP `price-compression` 二进制消息。
     *
     * @param payload Socket.IO 回调参数
     * @return 解压后的 JSON 字符串
     */
    public String decompressPriceFrame(Object payload) {
        byte[] frameBytes = toBytes(payload);
        int offset = frameBytes.length > 0 && frameBytes[0] == SOCKET_IO_BINARY_HEADER ? 1 : 0;
        try {
            return Snappy.uncompressString(frameBytes, offset, frameBytes.length - offset, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to decompress LP price-compression payload", exception);
        }
    }

    /**
     * 解析解压后的 LP 报价 JSON。
     *
     * @param quoteJson 解压后的 JSON
     * @return 外部原始报价列表
     */
    public List<ExternalRawQuote> parseQuotes(String quoteJson) {
        try {
            JsonNode root = objectMapper.readTree(quoteJson);
            List<ExternalRawQuote> quotes = new ArrayList<>();
            collectQuotes(root, quotes);
            return deduplicate(quotes);
        } catch (JacksonException exception) {
            return List.of();
        }
    }

    private String normalizeSocketBaseUrl(String domain, String socketPath) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("LP domain must not be blank");
        }
        String normalized = domain.trim();
        if (normalized.startsWith("https://")) {
            normalized = "wss://" + normalized.substring("https://".length());
        } else if (normalized.startsWith("http://")) {
            normalized = "ws://" + normalized.substring("http://".length());
        } else if (!normalized.startsWith("wss://") && !normalized.startsWith("ws://")) {
            normalized = "wss://" + normalized;
        }
        URI domainUri = URI.create(normalized);
        String path = domainUri.getRawPath();
        String effectivePath = path == null || path.isBlank() || "/".equals(path)
                ? normalizeSocketPath(socketPath)
                : normalizeSocketPath(path);
        return domainUri.getScheme() + "://" + domainUri.getRawAuthority() + effectivePath;
    }

    private String normalizeSocketPath(String socketPath) {
        String normalized = socketPath == null || socketPath.isBlank() ? "/safe/socket.io/" : socketPath.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        return normalized;
    }

    static String encrypt(String message, String appId, String secretKey, byte[] randomBytes) {
        if (randomBytes == null || randomBytes.length != AES_RANDOM_BYTES) {
            throw new IllegalArgumentException("LP encrypt random bytes must be 16 bytes");
        }
        try {
            byte[] key = decodeAesKey(secretKey);
            byte[] messageBytes = (message == null ? "" : message).getBytes(StandardCharsets.UTF_8);
            byte[] appIdBytes = (appId == null ? "" : appId).getBytes(StandardCharsets.UTF_8);
            ByteBuffer lengthBuffer = ByteBuffer.allocate(Integer.BYTES).putInt(messageBytes.length);
            ByteArrayOutputStream plain = new ByteArrayOutputStream();
            plain.writeBytes(randomBytes);
            plain.writeBytes(lengthBuffer.array());
            plain.writeBytes(messageBytes);
            plain.writeBytes(appIdBytes);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(key, 0, AES_IV_BYTES)
            );
            return Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray()));
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("Unable to encrypt LP socket token", exception);
        }
    }

    static String sha1Sorted(String token, String timestamp, String nonce, String encrypt) {
        List<String> values = new ArrayList<>(List.of(
                token == null ? "" : token,
                timestamp == null ? "" : timestamp,
                nonce == null ? "" : nonce,
                encrypt == null ? "" : encrypt
        ));
        values.sort(String::compareTo);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(String.join("", values).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-1 digest is unavailable", exception);
        }
    }

    private static byte[] decodeAesKey(String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("LP secretKey must not be blank");
        }
        String normalized = secretKey.trim();
        int padding = normalized.length() % 4;
        if (padding > 0) {
            normalized = normalized + "=".repeat(4 - padding);
        }
        byte[] key = Base64.getDecoder().decode(normalized);
        if (key.length != AES_KEY_BYTES) {
            throw new IllegalArgumentException("LP secretKey must decode to 32 bytes");
        }
        return key;
    }

    private String randomNonce() {
        byte[] bytes = new byte[4];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes).substring(0, NONCE_LENGTH);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private List<String> normalizeSymbols(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank()) {
                continue;
            }
            String normalizedSymbol = normalizeTicker(symbol);
            if (!normalizedSymbol.isBlank() && !normalized.contains(normalizedSymbol)) {
                normalized.add(normalizedSymbol);
            }
        }
        return List.copyOf(normalized);
    }

    private byte[] toBytes(Object payload) {
        if (payload instanceof byte[] bytes) {
            return bytes;
        }
        if (payload instanceof ByteBuffer byteBuffer) {
            ByteBuffer duplicate = byteBuffer.slice();
            byte[] bytes = new byte[duplicate.remaining()];
            duplicate.get(bytes);
            return bytes;
        }
        throw new IllegalArgumentException("Unsupported LP price-compression payload type: "
                + (payload == null ? "null" : payload.getClass().getName()));
    }

    private void collectQuotes(JsonNode node, List<ExternalRawQuote> quotes) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectQuotes(child, quotes));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        toQuote(node).ifPresent(quotes::add);
        collectQuotes(node.get("data"), quotes);
        collectQuotes(node.get("prices"), quotes);
        collectQuotes(node.get("priceList"), quotes);
        collectQuotes(node.get("quotes"), quotes);
    }

    private java.util.Optional<ExternalRawQuote> toQuote(JsonNode node) {
        String ticker = firstText(node, SYMBOL_FIELDS).map(this::normalizeTicker).orElse(null);
        BigDecimal bid = firstDecimal(node, BID_FIELDS).orElse(null);
        BigDecimal ask = firstDecimal(node, ASK_FIELDS).orElse(null);
        OffsetDateTime timestamp = firstTimestamp(node, TIMESTAMP_FIELDS).orElse(null);
        if (ticker == null || ticker.isBlank() || bid == null || ask == null || timestamp == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ExternalRawQuote(ticker, bid, ask, timestamp, SOURCE));
    }

    private java.util.Optional<String> firstText(JsonNode node, List<String> fieldNames) {
        return fieldNames.stream()
                .map(node::get)
                .filter(field -> field != null && !field.isNull())
                .map(JsonNode::asText)
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    private java.util.Optional<BigDecimal> firstDecimal(JsonNode node, List<String> fieldNames) {
        return fieldNames.stream()
                .map(node::get)
                .filter(field -> field != null && !field.isNull())
                .map(this::parseDecimal)
                .filter(value -> value != null)
                .findFirst();
    }

    private BigDecimal parseDecimal(JsonNode field) {
        try {
            return field == null || field.isNull() ? null : new BigDecimal(field.asText());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private java.util.Optional<OffsetDateTime> firstTimestamp(JsonNode node, List<String> fieldNames) {
        return fieldNames.stream()
                .map(node::get)
                .filter(field -> field != null && !field.isNull())
                .map(this::parseTimestamp)
                .filter(value -> value != null)
                .findFirst();
    }

    private OffsetDateTime parseTimestamp(JsonNode field) {
        String rawText = field.asText();
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        try {
            if (rawText.matches("^\\d{10,}$")) {
                long epochValue = Long.parseLong(rawText);
                if (rawText.length() == 10) {
                    return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochValue), ZoneOffset.UTC);
                }
                return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochValue), ZoneOffset.UTC);
            }
            return OffsetDateTime.parse(rawText);
        } catch (DateTimeParseException | NumberFormatException exception) {
            return null;
        }
    }

    private String normalizeTicker(String rawTicker) {
        return rawTicker == null ? "" : rawTicker.trim();
    }

    private List<ExternalRawQuote> deduplicate(List<ExternalRawQuote> quotes) {
        Set<String> seenKeys = new LinkedHashSet<>();
        List<ExternalRawQuote> deduplicated = new ArrayList<>();
        for (ExternalRawQuote quote : quotes) {
            String key = quote.ticker() + "|" + quote.ts() + "|" + quote.bid() + "|" + quote.ask() + "|" + quote.source();
            if (seenKeys.add(key)) {
                deduplicated.add(quote);
            }
        }
        return List.copyOf(deduplicated);
    }
}
