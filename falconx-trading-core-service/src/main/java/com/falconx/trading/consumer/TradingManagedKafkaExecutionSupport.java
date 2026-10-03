package com.falconx.trading.consumer;

import com.falconx.infrastructure.kafka.KafkaEventMessageSupport;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * trading-core Kafka 自管线程执行支持。
 *
 * <p>该组件负责把低频 Kafka 业务消费链路从 listener 回调线程切到 trading-core
 * 自管执行器中执行，并显式恢复 `traceId` 与 TCCL。
 *
 * <p>当前实现仍同步等待执行结果，确保异常继续回抛给 Kafka 容器，
 * 不改变现有 at-least-once 重试语义。
 */
@Component
public class TradingManagedKafkaExecutionSupport {

    private final ExecutorService tradingLowFrequencyKafkaExecutor;

    public TradingManagedKafkaExecutionSupport(
            @Qualifier("tradingLowFrequencyKafkaExecutor") ExecutorService tradingLowFrequencyKafkaExecutor
    ) {
        this.tradingLowFrequencyKafkaExecutor = tradingLowFrequencyKafkaExecutor;
    }

    /**
     * 在 trading-core 自管线程上执行业务消费动作。
     *
     * @param traceIdHeader Kafka 头里的 traceId
     * @param action 具体业务动作
     */
    public void execute(String traceIdHeader, ThrowingRunnable action) {
        String traceId = KafkaEventMessageSupport.bindTraceId(traceIdHeader);
        ClassLoader callerClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            tradingLowFrequencyKafkaExecutor.submit(() -> {
                runOnManagedThread(traceId, callerClassLoader, action);
                return null;
            }).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while processing trading Kafka event", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Unable to process trading Kafka event", cause);
        } finally {
            KafkaEventMessageSupport.clearTraceId();
        }
    }

    private void runOnManagedThread(String traceId,
                                    ClassLoader callerClassLoader,
                                    ThrowingRunnable action) throws Exception {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        KafkaEventMessageSupport.bindTraceId(traceId);
        Thread.currentThread().setContextClassLoader(callerClassLoader);
        try {
            action.run();
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
            KafkaEventMessageSupport.clearTraceId();
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
