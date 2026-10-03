package com.falconx.wallet.client;

import com.falconx.wallet.config.WalletServiceProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link HttpTronChainClient} HTTP API 解析测试。
 */
class HttpTronChainClientTests {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldFetchLatestBlockAndTransactionInfoLogs() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/wallet/getnowblock", exchange -> respondJson(exchange, """
                {
                  "block_header": {
                    "raw_data": {
                      "number": 123
                    }
                  }
                }
                """));
        server.createContext("/wallet/gettransactioninfobyblocknum", exchange -> respondJson(exchange, """
                {
                  "transactionInfo": [
                    {
                      "id": "0xabc",
                      "blockNumber": 123,
                      "result": "SUCESS",
                      "log": [
                        {
                          "address": "a614f803b6fd780986a42c78ec9c7f77e6ded13c",
                          "topics": [
                            "ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                            "00000000000000000000000079309abcff2cf531070ca9222a1f72c4a5136874",
                            "00000000000000000000000081b64b1c09d448d25c9eeb3ee3b8f3348a694c96"
                          ],
                          "data": "0000000000000000000000000000000000000000000000000000000005f5e100"
                        }
                      ]
                    }
                  ]
                }
                """));
        server.start();

        WalletServiceProperties.Chain chain = new WalletServiceProperties.Chain();
        chain.setRpcUrl(URI.create("http://localhost:" + server.getAddress().getPort()));
        HttpTronChainClient client = new HttpTronChainClient(chain, JsonMapper.builder().build());

        Assertions.assertEquals(123L, client.fetchLatestBlockNumber());
        List<TronChainClient.TronTransactionInfo> infos = client.fetchTransactionInfosByBlockNumber(123L);

        Assertions.assertEquals(1, infos.size());
        Assertions.assertEquals("0xabc", infos.getFirst().txHash());
        Assertions.assertEquals(123L, infos.getFirst().blockNumber());
        Assertions.assertEquals(1, infos.getFirst().logs().size());
        Assertions.assertEquals(
                "a614f803b6fd780986a42c78ec9c7f77e6ded13c",
                infos.getFirst().logs().getFirst().contractAddress()
        );
    }

    @Test
    void shouldUseSolidityEndpointWhenConfigured() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/wallet/getnowblock", exchange -> respondJson(exchange, """
                {
                  "block_header": {
                    "raw_data": {
                      "number": 456
                    }
                  }
                }
                """));
        server.createContext("/walletsolidity/gettransactioninfobyblocknum", exchange -> respondJson(exchange, """
                [
                  {
                    "id": "0xdef",
                    "blockNumber": 456,
                    "log": [
                      {
                        "address": "a614f803b6fd780986a42c78ec9c7f77e6ded13c",
                        "topics": [
                          "ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
                        ],
                        "data": "0000000000000000000000000000000000000000000000000000000005f5e100"
                      }
                    ]
                  }
                ]
                """));
        server.start();

        WalletServiceProperties.Chain chain = new WalletServiceProperties.Chain();
        chain.setRpcUrl(URI.create("http://localhost:" + server.getAddress().getPort()));
        chain.setSolidityRpcUrl(URI.create("http://localhost:" + server.getAddress().getPort()));
        HttpTronChainClient client = new HttpTronChainClient(chain, JsonMapper.builder().build());

        Assertions.assertEquals(456L, client.fetchLatestBlockNumber());
        List<TronChainClient.TronTransactionInfo> infos = client.fetchTransactionInfosByBlockNumber(456L);

        Assertions.assertEquals(1, infos.size());
        Assertions.assertEquals("0xdef", infos.getFirst().txHash());
    }

    private static void respondJson(HttpExchange exchange, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
