package com.falconx.trading.application;

import com.falconx.trading.config.TradingCoreServiceProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 价格 tick 执行器配置。
 *
 * <p>市场价格事件的数据库触发链路不能直接跑在 Kafka listener 线程里，
 * 必须切到 trading-core 自管线程后再执行，以隔离高频回调线程与 owner 写路径。
 */
@Configuration
public class TradingPriceTickExecutionConfiguration {

    /**
     * 按 symbol 分区的单线程 worker 池：同 symbol 串行，不同 symbol 可并行。
     */
    @Bean(name = "tradingPriceTickExecutor", destroyMethod = "shutdown")
    public SymbolPartitionedPriceTickExecutor tradingPriceTickExecutor(TradingCoreServiceProperties properties,
                                                                       ObjectProvider<MeterRegistry> meterRegistry) {
        SymbolPartitionedPriceTickExecutor executor =
                new SymbolPartitionedPriceTickExecutor(properties.getKafka().getMarketPriceTickWorkerCount());
        meterRegistry.ifAvailable(registry -> registerQueueMetrics(registry, executor));
        return executor;
    }

    private void registerQueueMetrics(MeterRegistry registry, SymbolPartitionedPriceTickExecutor executor) {
        Gauge.builder("falconx.trading.tick.worker.queue.total", executor,
                        SymbolPartitionedPriceTickExecutor::totalQueuedTaskCount)
                .description("trading-core price tick worker 总排队任务数")
                .baseUnit("tasks")
                .register(registry);
        for (int i = 0; i < executor.workerCount(); i++) {
            int workerIndex = i;
            Gauge.builder("falconx.trading.tick.worker.queue.size", executor,
                            e -> e.queuedTaskCount(workerIndex))
                    .tag("worker", String.valueOf(workerIndex))
                    .description("trading-core price tick 单 worker 排队任务数")
                    .baseUnit("tasks")
                    .register(registry);
        }
    }
}
