package com.falconx.wallet.client;

import com.falconx.wallet.config.WalletServiceProperties;
import com.falconx.wallet.util.WalletLogSanitizer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 基于 java-tron HTTP API 的 TRON 只读客户端。
 */
public class HttpTronChainClient implements TronChainClient {

    private static final Logger log = LoggerFactory.getLogger(HttpTronChainClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final WalletServiceProperties.Chain chainProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpTronChainClient(WalletServiceProperties.Chain chainProperties, ObjectMapper objectMapper) {
        this(chainProperties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    HttpTronChainClient(WalletServiceProperties.Chain chainProperties,
                        ObjectMapper objectMapper,
                        HttpClient httpClient) {
        this.chainProperties = chainProperties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public long fetchLatestBlockNumber() throws IOException {
        JsonNode root = postJson(chainProperties.getRpcUrl(), "wallet/getnowblock", Map.of("visible", false));
        long blockNumber = root.path("block_header").path("raw_data").path("number").asLong(-1L);
        if (blockNumber < 0) {
            throw new IOException("TRON latest block response missing block_header.raw_data.number");
        }
        return blockNumber;
    }

    @Override
    public List<TronTransactionInfo> fetchTransactionInfosByBlockNumber(long blockNumber) throws IOException {
        JsonNode root = postJson(transactionInfoRpcUrl(), transactionInfoEndpointPath(), Map.of(
                "num", blockNumber,
                "visible", false
        ));
        JsonNode items = root.isArray() ? root : root.path("transactionInfo");
        if (!items.isArray()) {
            log.debug("wallet.tron.rpc.blockInfo.empty blockNumber={} rpcUrl={}",
                    blockNumber,
                    WalletLogSanitizer.maskRpcUrl(transactionInfoRpcUrl()));
            return List.of();
        }

        List<TronTransactionInfo> transactionInfos = new ArrayList<>();
        for (JsonNode item : items) {
            String txHash = text(item.path("id"));
            if (txHash == null) {
                continue;
            }
            if ("FAILED".equalsIgnoreCase(text(item.path("result")))) {
                continue;
            }
            long infoBlockNumber = item.path("blockNumber").asLong(blockNumber);
            List<TronLog> logs = parseLogs(item.path("log"));
            if (!logs.isEmpty()) {
                transactionInfos.add(new TronTransactionInfo(txHash, infoBlockNumber, logs));
            }
        }
        return transactionInfos;
    }

    private JsonNode postJson(URI rpcUrl, String endpointPath, Map<String, Object> payload) throws IOException {
        URI endpoint = resolveEndpoint(rpcUrl, endpointPath);
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            if (chainProperties.getApiKey() != null && !chainProperties.getApiKey().isBlank()) {
                requestBuilder.header("TRON-PRO-API-KEY", chainProperties.getApiKey());
            }
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("TRON RPC HTTP " + response.statusCode()
                        + " url=" + WalletLogSanitizer.maskRpcUrl(endpoint));
            }
            String body = response.body();
            if (body == null || body.isBlank()) {
                throw new IOException("TRON RPC empty response url="
                        + WalletLogSanitizer.maskRpcUrl(endpoint));
            }
            String trimmed = body.stripLeading();
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                throw new IOException("TRON RPC non-json response url="
                        + WalletLogSanitizer.maskRpcUrl(endpoint));
            }
            return objectMapper.readTree(body);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("TRON RPC interrupted url="
                    + WalletLogSanitizer.maskRpcUrl(endpoint), ex);
        }
    }

    private List<TronLog> parseLogs(JsonNode logsNode) {
        if (!logsNode.isArray()) {
            return List.of();
        }
        List<TronLog> logs = new ArrayList<>();
        for (JsonNode logNode : logsNode) {
            String contractAddress = text(logNode.path("address"));
            String data = text(logNode.path("data"));
            JsonNode topicsNode = logNode.path("topics");
            if (contractAddress == null || data == null || !topicsNode.isArray()) {
                continue;
            }
            List<String> topics = new ArrayList<>();
            for (JsonNode topicNode : topicsNode) {
                String topic = text(topicNode);
                if (topic != null) {
                    topics.add(topic);
                }
            }
            logs.add(new TronLog(contractAddress, topics, data));
        }
        return logs;
    }

    private URI transactionInfoRpcUrl() {
        return chainProperties.getSolidityRpcUrl() == null
                ? chainProperties.getRpcUrl()
                : chainProperties.getSolidityRpcUrl();
    }

    private String transactionInfoEndpointPath() {
        return chainProperties.getSolidityRpcUrl() == null
                ? "wallet/gettransactioninfobyblocknum"
                : "walletsolidity/gettransactioninfobyblocknum";
    }

    private URI resolveEndpoint(URI rpcUrl, String endpointPath) {
        String base = rpcUrl.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/" + endpointPath);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value;
    }
}
