package com.falconx.wallet.client;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.falconx.wallet.config.WalletServiceProperties;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.http.HttpService;
import org.web3j.protocol.websocket.WebSocketService;

/**
 * {@link WalletBlockchainClientFactory} 测试。
 *
 * <p>该测试只验证工厂的协议分派行为，不连接真实链节点。
 * Stage 6A 当前要保证的是：应用在接到 `http/https` 与 `ws/wss` 端点时，
 * 能走到正确的底层客户端类型；真实链节点联通性由后续集成联调覆盖。
 */
class WalletBlockchainClientFactoryTests {

    private final WalletBlockchainClientFactory walletBlockchainClientFactory = new WalletBlockchainClientFactory();

    @Test
    void shouldCreateHttpServiceForHttpRpcUrl() {
        assertInstanceOf(
                HttpService.class,
                walletBlockchainClientFactory.createEvmService(URI.create("http://localhost:8545"))
        );
    }

    @Test
    void shouldCreateWebSocketServiceForWssRpcUrl() {
        WalletBlockchainClientFactory factory = new WalletBlockchainClientFactory() {
            @Override
            WebSocketService createWebSocketService(URI rpcUrl) {
                return new WebSocketService(rpcUrl.toString(), false);
            }
        };
        assertInstanceOf(
                WebSocketService.class,
                factory.createEvmService(URI.create("wss://eth-sepolia.g.alchemy.com/v2/test"))
        );
    }

    @Test
    void shouldRejectUnsupportedRpcScheme() {
        assertThrows(
                IllegalArgumentException.class,
                () -> walletBlockchainClientFactory.createEvmService(URI.create("ftp://localhost:21"))
        );
    }

    @Test
    void shouldCreateHttpTronClientForTronRpcUrl() {
        WalletServiceProperties.Chain chain = new WalletServiceProperties.Chain();
        chain.setRpcUrl(URI.create("https://tron-test.example.org"));

        assertInstanceOf(
                HttpTronChainClient.class,
                walletBlockchainClientFactory.createTronClient(chain)
        );
    }

    @Test
    void shouldMaskSensitiveRpcUrlInWebSocketFailureMessage() {
        String message = WalletBlockchainClientFactory.webSocketConnectFailedMessage(
                URI.create("wss://eth-mainnet.g.alchemy.com/v2/secret-token?apikey=secret")
        );

        org.junit.jupiter.api.Assertions.assertEquals(
                "wallet.evm.websocket.connect.failed url=wss://eth-mainnet.g.alchemy.com/v2/[REDACTED]",
                message
        );
        org.junit.jupiter.api.Assertions.assertFalse(message.contains("secret-token"));
        org.junit.jupiter.api.Assertions.assertFalse(message.contains("apikey=secret"));
    }
}
