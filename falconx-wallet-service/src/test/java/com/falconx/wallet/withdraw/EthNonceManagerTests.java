package com.falconx.wallet.withdraw;

import java.io.IOException;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;

/**
 * STAGE-7-WITHDRAW Phase 3 R6：{@link EthNonceManager} 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>TC-WD-130 启动从 RPC 拉 nonce</li>
 *   <li>TC-WD-131 10 并发线程递增唯一性</li>
 *   <li>TC-WD-132 reset 后重新拉链</li>
 * </ul>
 */
class EthNonceManagerTests {

    private static final String FROM_ADDRESS = "0xAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaa";

    /** TC-WD-130：启动 mock 返回 5；首次 nextNonce 返回 5，内部计数器自增到 6。 */
    @Test
    void shouldFetchInitialNonceFromRpcAndIncrement() throws Exception {
        Web3j web3j = stubEthGetTransactionCount(BigInteger.valueOf(5));
        EthNonceManager manager = new EthNonceManager(web3j);

        Assertions.assertEquals(5L, manager.nextNonce(FROM_ADDRESS));
        Assertions.assertEquals(6L, manager.nextNonce(FROM_ADDRESS));
        // 后续调用不再拉链 RPC（缓存）
        Mockito.verify(web3j, Mockito.times(1)).ethGetTransactionCount(
                Mockito.anyString(), Mockito.eq(DefaultBlockParameterName.PENDING));
    }

    /** TC-WD-131：10 并发线程调用 nextNonce → 返回值集合 = {10..19}，无重复。 */
    @Test
    void shouldAllocateUniqueIncrementingNoncesUnderConcurrency() throws Exception {
        Web3j web3j = stubEthGetTransactionCount(BigInteger.valueOf(10));
        EthNonceManager manager = new EthNonceManager(web3j);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> collected = java.util.Collections.synchronizedSet(new HashSet<>());
        CountDownLatch done = new CountDownLatch(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    collected.add(manager.nextNonce(FROM_ADDRESS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        Assertions.assertTrue(done.await(10, TimeUnit.SECONDS), "并发分配应在 10s 内完成");
        executor.shutdownNow();

        Assertions.assertEquals(threadCount, collected.size(), "10 个 nonce 必须全部唯一");
        Set<Long> expected = new HashSet<>();
        for (long n = 10L; n < 20L; n++) {
            expected.add(n);
        }
        Assertions.assertEquals(expected, collected, "返回值集合应为 {10..19}");
    }

    /** TC-WD-132：reset 重新拉链；第二次 RPC mock 返回 15，reset 后 nextNonce=15（不是基于第一次的 11）。 */
    @Test
    void shouldResetByRefetchingNonceFromRpc() throws Exception {
        Web3j web3j = Mockito.mock(Web3j.class);
        // 两次 RPC 调用：第一次返回 10，第二次（reset）返回 15
        EthGetTransactionCount first = new EthGetTransactionCount();
        first.setResult("0xa"); // 10
        EthGetTransactionCount second = new EthGetTransactionCount();
        second.setResult("0xf"); // 15
        @SuppressWarnings("unchecked")
        Request<?, EthGetTransactionCount> req1 = Mockito.mock(Request.class);
        @SuppressWarnings("unchecked")
        Request<?, EthGetTransactionCount> req2 = Mockito.mock(Request.class);
        Mockito.when(req1.send()).thenReturn(first);
        Mockito.when(req2.send()).thenReturn(second);
        Mockito.doReturn(req1, req2).when(web3j).ethGetTransactionCount(
                Mockito.anyString(), Mockito.eq(DefaultBlockParameterName.PENDING));

        EthNonceManager manager = new EthNonceManager(web3j);

        Assertions.assertEquals(10L, manager.nextNonce(FROM_ADDRESS));
        Assertions.assertEquals(11L, manager.nextNonce(FROM_ADDRESS));

        manager.reset(FROM_ADDRESS);

        // reset 后从链上重新拉 15，覆盖原计数器
        Assertions.assertEquals(15L, manager.nextNonce(FROM_ADDRESS));
        Assertions.assertEquals(16L, manager.nextNonce(FROM_ADDRESS));
    }

    private static Web3j stubEthGetTransactionCount(BigInteger initialNonce) throws IOException {
        Web3j web3j = Mockito.mock(Web3j.class);
        EthGetTransactionCount resp = new EthGetTransactionCount();
        resp.setResult("0x" + initialNonce.toString(16));
        @SuppressWarnings("unchecked")
        Request<?, EthGetTransactionCount> req = Mockito.mock(Request.class);
        Mockito.when(req.send()).thenReturn(resp);
        Mockito.doReturn(req).when(web3j).ethGetTransactionCount(
                Mockito.anyString(), Mockito.eq(DefaultBlockParameterName.PENDING));
        return web3j;
    }
}
