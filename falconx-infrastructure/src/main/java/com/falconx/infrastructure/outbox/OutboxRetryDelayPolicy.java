package com.falconx.infrastructure.outbox;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Outbox 重试退避策略。
 *
 * <p>该策略把三个 owner 服务的 Outbox 重试时序统一为同一套技术规则，避免每个服务各自维护一份
 * “固定档位 + 固定秒数”的实现，后续再出现策略变更时需要分别修改三处代码。
 *
 * <p>当前冻结规则如下：
 *
 * <ul>
 *   <li>第 1 次失败：基础退避 `5s`</li>
 *   <li>第 2 次失败：基础退避 `30s`</li>
 *   <li>第 3 次失败：基础退避 `120s`</li>
 *   <li>第 4 次及以后：基础退避 `1800s`（30 分钟）</li>
 *   <li>每一档统一叠加 `±20%` jitter，避免大量失败消息在同一秒集中重试</li>
 * </ul>
 *
 * <p>这里返回的是“已经叠加抖动后的最终秒数”，调用方只需要把它加到 `nextRetryAt` 即可。
 */
public final class OutboxRetryDelayPolicy {

    private static final double MIN_JITTER_FACTOR = 0.8d;
    private static final double MAX_JITTER_FACTOR = 1.2d;

    private OutboxRetryDelayPolicy() {
    }

    /**
     * 计算下一次重试应等待的秒数。
     *
     * @param nextRetryCount 即将进入的重试次数
     * @return 叠加 jitter 后的最终退避秒数
     */
    public static long resolveDelaySeconds(int nextRetryCount) {
        long baseDelaySeconds = switch (nextRetryCount) {
            case 1 -> 5L;
            case 2 -> 30L;
            case 3 -> 120L;
            default -> 1800L;
        };
        double jitterFactor = ThreadLocalRandom.current().nextDouble(MIN_JITTER_FACTOR, MAX_JITTER_FACTOR);
        long jitteredDelay = Math.round(baseDelaySeconds * jitterFactor);
        return Math.max(1L, jitteredDelay);
    }
}
