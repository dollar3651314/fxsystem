package com.falconx.console.security;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@link HighRiskPermissionRegistry} 单元测试，覆盖 R6 TC-CONSOLE-049 高风险标记。
 */
class HighRiskPermissionRegistryTests {

    @Test
    void shouldClassifyKnownHighRiskCodesAsHighRisk() {
        Assertions.assertEquals("HIGH_RISK",
                HighRiskPermissionRegistry.resolveRiskLevel("customer:balance:adjust"));
        Assertions.assertEquals("HIGH_RISK",
                HighRiskPermissionRegistry.resolveRiskLevel("customer:freeze"));
        // STAGE-7-WITHDRAW Phase 4：legacy "withdraw:approve" 替换为 "withdraw:review" + "withdraw:emergency-cancel"
        Assertions.assertEquals("HIGH_RISK",
                HighRiskPermissionRegistry.resolveRiskLevel("withdraw:review"));
        Assertions.assertEquals("HIGH_RISK",
                HighRiskPermissionRegistry.resolveRiskLevel("withdraw:emergency-cancel"));
        Assertions.assertEquals("HIGH_RISK",
                HighRiskPermissionRegistry.resolveRiskLevel("risk-action:activate"));
        Assertions.assertEquals("HIGH_RISK",
                HighRiskPermissionRegistry.resolveRiskLevel("trading-monitor:manual-liquidate"));
        Assertions.assertEquals("HIGH_RISK",
                HighRiskPermissionRegistry.resolveRiskLevel("trading-monitor:auto-liquidate:pause"));
        Assertions.assertEquals("HIGH_RISK",
                HighRiskPermissionRegistry.resolveRiskLevel("symbol:update"));
        Assertions.assertEquals("HIGH_RISK",
                HighRiskPermissionRegistry.resolveRiskLevel("symbol:suspend"));
        Assertions.assertEquals("HIGH_RISK",
                HighRiskPermissionRegistry.resolveRiskLevel("symbol:swap-rate:update"));
        Assertions.assertEquals("HIGH_RISK",
                HighRiskPermissionRegistry.resolveRiskLevel("symbol:quote-mapping:update"));
        Assertions.assertEquals("HIGH_RISK",
                HighRiskPermissionRegistry.resolveRiskLevel("symbol:group-visibility:update"));
    }

    @Test
    void shouldClassifyOrdinaryCodesAsLow() {
        Assertions.assertEquals("LOW",
                HighRiskPermissionRegistry.resolveRiskLevel("customer:view"));
        Assertions.assertEquals("LOW",
                HighRiskPermissionRegistry.resolveRiskLevel("admin-user:view"));
        Assertions.assertEquals("LOW",
                HighRiskPermissionRegistry.resolveRiskLevel("audit:operation-log:view"));
    }

    @Test
    void shouldClassifyUnknownCodeAsLow() {
        Assertions.assertEquals("LOW",
                HighRiskPermissionRegistry.resolveRiskLevel("unknown:never-seen"));
    }
}
