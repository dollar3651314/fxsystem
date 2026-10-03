package com.falconx.wallet.client;

import java.io.IOException;
import java.util.List;

/**
 * TRON 链只读客户端抽象。
 *
 * <p>监听器只依赖区块高度和交易回执日志，不直接绑定具体 RPC 传输实现。
 */
public interface TronChainClient extends AutoCloseable {

    long fetchLatestBlockNumber() throws IOException;

    List<TronTransactionInfo> fetchTransactionInfosByBlockNumber(long blockNumber) throws IOException;

    @Override
    default void close() {
    }

    record TronTransactionInfo(String txHash, long blockNumber, List<TronLog> logs) {
    }

    record TronLog(String contractAddress, List<String> topics, String data) {
    }
}
