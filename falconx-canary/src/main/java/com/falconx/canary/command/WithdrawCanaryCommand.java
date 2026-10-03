package com.falconx.canary.command;

import com.falconx.canary.report.CanaryReport;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * STAGE-11-OBS-RECON §11.2 canary withdraw 命令骨架。
 *
 * <p>计划链路：login → 创建白名单 → POST /me/withdraw → admin 模拟 approve →
 * 60s 内见 status=COMPLETED。R6 二轮补真代码（依赖 KMS / 测试网 ETH RPC 配置）。
 */
@Component
public class WithdrawCanaryCommand implements CanaryCommand {

    @Override
    public String name() {
        return "withdraw";
    }

    @Override
    public CanaryReport run(List<String> args) {
        return CanaryReport.fail(name(),
                "withdraw canary 骨架占位，R6 二轮补真代码",
                Map.of("status", "STUB"));
    }
}
