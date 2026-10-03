package com.falconx.canary.command;

import com.falconx.canary.report.CanaryReport;
import java.util.List;

/**
 * Canary 命令统一接口。
 *
 * <p>各命令实现单独提供 {@link #name()}，{@code CanaryApplication} 按 CLI argv[0] 分发。
 */
public interface CanaryCommand {

    /** 命令名（CLI 第一个参数）。 */
    String name();

    /** 执行命令并返回报告。args 是 CLI argv[1..] 剩余参数。 */
    CanaryReport run(List<String> args);
}
