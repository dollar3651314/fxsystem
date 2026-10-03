package com.falconx.infrastructure.id;

import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 单节点雪花 ID 生成器。
 *
 * <p>一期部署模型仍以单区域、少量实例为主，因此这里采用单节点默认机器位配置：
 *
 * <ul>
 *   <li>41 位时间戳</li>
 *   <li>5 位 datacenter</li>
 *   <li>5 位 worker</li>
 *   <li>12 位序列号</li>
 * </ul>
 *
 * <p>该实现满足当前 Stage 5 的 owner 表主键生成需求，并为后续多实例部署保留位宽。
 */
@Component
public class SnowflakeIdGenerator implements IdGenerator {

    /**
     * FalconX 自定义起始纪元，缩短时间戳占用位数。
     */
    private static final long EPOCH_MILLIS = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    /**
     * 时钟回拨容忍上限（毫秒）。NTP 校正 / VM 暂停 / 容器迁移都可能让 wall clock 短暂回拨几毫秒到
     * 几十毫秒；此区间内 busy-wait 等到 lastTimestamp 再生成，避免误杀正常 ID 生成。
     * 超过该阈值视为机器异常，仍抛 IllegalStateException 让上层告警/重启。
     */
    private static final long MAX_CLOCK_DRIFT_MILLIS = 200L;

    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    private final long workerId;
    private final long datacenterId;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator() {
        this(1L, 1L);
    }

    /**
     * 提供显式机器位配置，便于后续扩展到多实例部署。
     *
     * @param workerId 工作节点 ID
     * @param datacenterId 数据中心 ID
     */
    public SnowflakeIdGenerator(long workerId, long datacenterId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId out of range: " + workerId);
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException("datacenterId out of range: " + datacenterId);
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    @Override
    public synchronized long nextId() {
        long timestamp = currentTimestamp();
        // 2026-05-20 加固：原实现对任何时钟回拨都直接抛，导致 NTP / VM 暂停 / 容器迁移触发崩溃。
        // 改为：回拨幅度 ≤ MAX_CLOCK_DRIFT_MILLIS 时 busy-wait 等到 lastTimestamp+1ms；超过仍抛
        // 但带详细信息便于排查。
        if (timestamp < lastTimestamp) {
            long drift = lastTimestamp - timestamp;
            if (drift > MAX_CLOCK_DRIFT_MILLIS) {
                throw new IllegalStateException(
                        "Clock moved backwards too far: drift=" + drift + "ms"
                                + " lastTimestamp=" + lastTimestamp
                                + " currentTimestamp=" + timestamp
                                + " (tolerance=" + MAX_CLOCK_DRIFT_MILLIS + "ms)");
            }
            // 短暂回拨：等到时钟追上 lastTimestamp 再继续
            timestamp = waitNextMillis(lastTimestamp);
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0L) {
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;
        return ((timestamp - EPOCH_MILLIS) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long currentTimestamp() {
        return System.currentTimeMillis();
    }

    private long waitNextMillis(long lastTimestampValue) {
        long timestamp = currentTimestamp();
        while (timestamp <= lastTimestampValue) {
            timestamp = currentTimestamp();
        }
        return timestamp;
    }
}
