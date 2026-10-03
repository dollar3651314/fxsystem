package com.falconx.canary.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canary 命令报告。
 *
 * <p>退出码语义：0=PASS / 1=FAIL / 2=PARTIAL_TIMEOUT。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CanaryReport(
        String command,
        String status,
        int exitCode,
        Instant startedAt,
        Instant finishedAt,
        long durationMillis,
        String message,
        Map<String, Object> details
) {

    public static CanaryReport pass(String command, Map<String, Object> details) {
        Instant now = Instant.now();
        return new CanaryReport(command, "PASS", 0, now, now, 0L, null, details);
    }

    public static CanaryReport pass(String command, Instant startedAt, Map<String, Object> details) {
        Instant finished = Instant.now();
        long dur = java.time.Duration.between(startedAt, finished).toMillis();
        return new CanaryReport(command, "PASS", 0, startedAt, finished, dur, null, details);
    }

    public static CanaryReport fail(String command, String message) {
        Instant now = Instant.now();
        return new CanaryReport(command, "FAIL", 1, now, now, 0L, message, new LinkedHashMap<>());
    }

    public static CanaryReport fail(String command, String message, Map<String, Object> details) {
        Instant now = Instant.now();
        return new CanaryReport(command, "FAIL", 1, now, now, 0L, message, details);
    }

    public static CanaryReport fail(String command, Instant startedAt, String message, Map<String, Object> details) {
        Instant finished = Instant.now();
        long dur = java.time.Duration.between(startedAt, finished).toMillis();
        return new CanaryReport(command, "FAIL", 1, startedAt, finished, dur, message, details);
    }

    public static CanaryReport partialTimeout(String command, Instant startedAt, String message, Map<String, Object> details) {
        Instant finished = Instant.now();
        long dur = java.time.Duration.between(startedAt, finished).toMillis();
        return new CanaryReport(command, "PARTIAL_TIMEOUT", 2, startedAt, finished, dur, message, details);
    }
}
