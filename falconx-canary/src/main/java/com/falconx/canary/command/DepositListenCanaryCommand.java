package com.falconx.canary.command;

import com.falconx.canary.report.CanaryReport;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * STAGE-11-OBS-RECON §11.2 canary deposit-listen 命令骨架。
 *
 * <p>计划链路：触发模拟链上 tx → 60s 内见 wallet outbox → trading-core t_deposit 落库。
 * R6 二轮补真代码（依赖 wallet 测试桩 trigger detected）。
 */
@Component
public class DepositListenCanaryCommand implements CanaryCommand {

    @Override
    public String name() {
        return "deposit-listen";
    }

    @Override
    public CanaryReport run(List<String> args) {
        return CanaryReport.fail(name(),
                "deposit-listen canary 骨架占位，R6 二轮补真代码",
                Map.of("status", "STUB"));
    }
}
