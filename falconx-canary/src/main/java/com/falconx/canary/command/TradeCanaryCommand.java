package com.falconx.canary.command;

import com.falconx.canary.report.CanaryReport;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * STAGE-11-OBS-RECON §11.2 canary trade 命令。
 *
 * <p>骨架占位。计划链路：login → POST /api/v1/me/orders 市价单 BTCUSDT 0.001 →
 * GET /api/v1/me/positions → POST /api/v1/me/positions/{id}/close → GET 账户余额。
 *
 * <p>R6 二轮补真代码（依赖 OrderTicket 测试数据 + symbol 配置）。
 */
@Component
public class TradeCanaryCommand implements CanaryCommand {

    @Override
    public String name() {
        return "trade";
    }

    @Override
    public CanaryReport run(List<String> args) {
        return CanaryReport.fail(name(),
                "trade canary 骨架占位，R6 二轮补真代码（详见 STAGE-11-OBS-RECON-design.md §3.2）",
                Map.of("status", "STUB"));
    }
}
