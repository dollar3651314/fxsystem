package com.falconx.canary.command;

import com.falconx.canary.report.CanaryReport;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * STAGE-11-OBS-RECON §11.2 canary price-alert 命令骨架。
 *
 * <p>计划链路：login → POST /me/price-alerts → quote tick 触发 → poll t_notification
 * 30s 内见 PRICE_ALERT_TRIGGERED 类型 notif。R6 二轮补真代码（依赖 quote stub）。
 */
@Component
public class PriceAlertCanaryCommand implements CanaryCommand {

    @Override
    public String name() {
        return "price-alert";
    }

    @Override
    public CanaryReport run(List<String> args) {
        return CanaryReport.fail(name(),
                "price-alert canary 骨架占位，R6 二轮补真代码",
                Map.of("status", "STUB"));
    }
}
