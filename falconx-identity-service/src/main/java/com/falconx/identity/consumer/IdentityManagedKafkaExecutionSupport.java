package com.falconx.identity.consumer;

import com.falconx.infrastructure.kafka.KafkaEventMessageSupport;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * identity-service Kafka 自管线程执行支持。
 *
 * <p>该组件把低频 Kafka 业务处理从 listener 回调线程切到 identity-service 自管线程，
 * 并恢复 `traceId` 与 TCCL，避免把事务链路直接挂在 Kafka 容器线程上。
 */
@Component
public class IdentityManagedKafkaExecutionSupport {

    private final ExecutorService identityKafkaExecutor;

    public IdentityManagedKafkaExecutionSupport(@Qualifier("identityKafkaExecutor") ExecutorService identityKafkaExecutor) {
        this.identityKafkaExecutor = identityKafkaExecutor;
    }

    /**
     * 在 identity-service 自管线程上执行业务消费动作。
     *
     * @param traceIdHeader Kafka 头中的 traceId
     * @param action 具体业务动作
     */
    public void execute(String traceIdHeader, ThrowingRunnable action) {
        String traceId = KafkaEventMessageSupport.bindTraceId(traceIdHeader);
        ClassLoader callerClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            identityKafkaExecutor.submit(() -> {
                runOnManagedThread(traceId, callerClassLoader, action);
                return null;
            }).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while processing identity Kafka event", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Unable to process identity Kafka event", cause);
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
