package com.falconx.canary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.falconx.canary.report.CanaryReport;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanaryReportTests {

    @Test
    void pass_assigns_exit_code_zero() {
        CanaryReport r = CanaryReport.pass("login", Map.of("ok", true));
        assertEquals("PASS", r.status());
        assertEquals(0, r.exitCode());
        assertEquals("login", r.command());
        assertNotNull(r.details());
    }

    @Test
    void fail_assigns_exit_code_one() {
        CanaryReport r = CanaryReport.fail("recon", "总数超阈值");
        assertEquals("FAIL", r.status());
        assertEquals(1, r.exitCode());
        assertEquals("总数超阈值", r.message());
    }

    @Test
    void partial_timeout_assigns_exit_code_two() {
        Instant started = Instant.now().minusSeconds(5);
        CanaryReport r = CanaryReport.partialTimeout("deposit-listen", started, "wait timeout", Map.of("waited", 60));
        assertEquals("PARTIAL_TIMEOUT", r.status());
        assertEquals(2, r.exitCode());
        assertEquals(Map.of("waited", 60), r.details());
    }
}
