package com.falconx.wallet.client;

import com.falconx.wallet.config.WalletServiceProperties;
import com.falconx.wallet.util.WalletLogSanitizer;
import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import okhttp3.OkHttpClient;
import org.p2p.solanaj.rpc.RpcClient;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.http.HttpService;
import org.web3j.protocol.websocket.WebSocketService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 钱包链客户端工厂。
 *
 * <p>该工厂统一负责创建各条链对应的 SDK 客户端，避免监听器各自拼装连接参数。
 * 当前阶段只创建客户端，不在工厂层执行轮询或解析逻辑。
 */
public class WalletBlockchainClientFactory {

    // 2026-05-27 FX-074-C：web3j HttpService 默认 OkHttpClient callTimeout=0（无总超时），
    // 单个 RPC 调用可能挂死拖垮扫块线程。显式设 connect/read/write/call timeout。
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HTTP_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration HTTP_WRITE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HTTP_CALL_TIMEOUT = Duration.ofSeconds(40);

    private final ObjectMapper objectMapper;

    public WalletBlockchainClientFactory() {
        this(JsonMapper.builder().findAndAddModules().build());
    }

    public WalletBlockchainClientFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 创建 EVM 链客户端。
     *
     * @param rpcUrl 节点 RPC 地址
     * @return Web3j 客户端
     */
    public Web3j createEvmClient(URI rpcUrl) {
        return Web3j.build(createEvmService(rpcUrl));
    }

    /**
     * 按 URI 协议选择底层 EVM 传输实现。
     *
     * <p>Stage 6A 的以太坊联调既要支持本地 `http` 节点，也要支持
     * Alchemy 这类 `wss` 端点。这里统一在工厂层完成协议分派，
     * 避免监听器自行判断传输类型并产生多处配置分叉。
     *
     * @param rpcUrl 节点 RPC 地址
     * @return 对应的 Web3j Service
     */
    Web3jService createEvmService(URI rpcUrl) {
        String scheme = rpcUrl.getScheme();
        if (scheme == null || scheme.isBlank()) {
            throw new IllegalArgumentException("wallet.evm.rpcUrl scheme is required");
        }
        return switch (scheme.toLowerCase(Locale.ROOT)) {
            case "http", "https" -> new HttpService(rpcUrl.toString(), buildHttpClient());
            case "ws", "wss" -> createWebSocketService(rpcUrl);
            default -> throw new IllegalArgumentException("wallet.evm.rpcUrl scheme is not supported: " + scheme);
        };
    }

    /**
     * 2026-05-27 FX-074-C：带显式超时的 OkHttpClient，避免单 RPC 无限挂起。
     */
    private OkHttpClient buildHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(HTTP_CONNECT_TIMEOUT)
                .readTimeout(HTTP_READ_TIMEOUT)
                .writeTimeout(HTTP_WRITE_TIMEOUT)
                .callTimeout(HTTP_CALL_TIMEOUT)
                .build();
    }

    /**
     * 创建并主动建立 WebSocket 连接。
     *
     * <p>Web3j 的 `WebSocketService` 在真正发起 JSON-RPC 调用或订阅前必须先显式连接。
     * 若这里不提前连接，监听器启动阶段会把无效客户端带入后续链路，直到首次 RPC 调用才暴露配置问题。
     *
     * @param rpcUrl WebSocket RPC 地址
     * @return 已建立连接的 WebSocketService
     */
    WebSocketService createWebSocketService(URI rpcUrl) {
        WebSocketService webSocketService = new WebSocketService(rpcUrl.toString(), false);
        try {
            webSocketService.connect();
        } catch (ConnectException ex) {
            throw new IllegalStateException(webSocketConnectFailedMessage(rpcUrl), ex);
        }
        return webSocketService;
    }

    static String webSocketConnectFailedMessage(URI rpcUrl) {
        return "wallet.evm.websocket.connect.failed url=" + WalletLogSanitizer.maskRpcUrl(rpcUrl);
    }

    /**
     * 创建 Solana RPC 客户端。
     *
     * @param rpcUrl 节点 RPC 地址
     * @return Solana RPC 客户端
     */
    public RpcClient createSolanaClient(URI rpcUrl) {
        return new RpcClient(rpcUrl.toString());
    }

    /**
     * 创建 Tron HTTP RPC 只读客户端。
     *
     * <p>当前监听只需要 `getnowblock` 和 `gettransactioninfobyblocknum`，
     * 使用 java-tron HTTP API 可以兼容本地 FullNode HTTP 端口和托管 HTTPS RPC。
     *
     * @param chainProperties Tron 链配置
     * @return Tron 只读客户端
     */
    public TronChainClient createTronClient(WalletServiceProperties.Chain chainProperties) {
        return new HttpTronChainClient(chainProperties, objectMapper);
    }
}
