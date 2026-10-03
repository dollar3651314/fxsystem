package com.falconx.console.security;

import java.util.Set;

/**
 * 高风险权限码静态注册表。
 *
 * <p>对应 [`管理端设计系统`](../../../../../../../../docs/design/falconx-console-DESIGN.md) §7.3 列表，
 * 决定 {@code t_admin_operation_log.risk_level} 与前端二次确认 Modal 的触发位置。
 *
 * <p>本阶段在代码中静态硬编码；后续如需运营动态调整，可在阶段 9 BBook 风控运营手册中
 * 引入运营端配置（{@code t_admin_high_risk_permission} 等表，本阶段不做）。
 */
public final class HighRiskPermissionRegistry {

    /** 阶段 1-7 已识别的高风险权限码集合。 */
    public static final Set<String> CODES = Set.of(
            "customer:balance:adjust",
            "customer:edit",
            "customer:freeze",
            // STAGE-7-WITHDRAW Phase 4：用 review + emergency-cancel 取代 legacy "withdraw:approve"
            // （管理端接口规范 §10.7 冻结；管理端架构.md / falconx-console-DESIGN.md 等历史文档仍引用
            // legacy 名称，待 R8 文档轮收口时一并修正）
            "withdraw:review",
            "withdraw:emergency-cancel",
            "risk-action:activate",
            "risk-config:update",
            "risk-market-config:update",
            "trading-monitor:manual-liquidate",
            "trading-monitor:auto-liquidate:pause",
            "symbol:update",
            "symbol:source:create",
            "symbol:source:update",
            "symbol:suspend",
            "symbol:swap-rate:update",
            "symbol:trading-hours:update",
            "symbol:holiday:update",
            "symbol:quote-mapping:update",
            "symbol:group-visibility:update",
            // STAGE-12-GROUP-MARKUP：用户组加点 CRUD（影响所有该组用户的报价 + 撮合 + PnL 口径）
            "symbol:group-markup:update",
            // STAGE-8-NOTIFICATION Phase 3：模板 CRUD（写）+ 手动发送
            "notification:template:manage",
            "notification:send",
            // STAGE-11-OBS-RECON Phase 3：手动标记入金对账已 resolved（运营对入金链路最终裁决）
            "reconciliation:resolve",
            // STAGE-14C2 Task 8：tier CRUD（写）—— 改杠杆/MM 档位直接影响开仓校验、保证金与强平口径
            "tier:edit",
            // STAGE-14D3b Task 1：平台配置写入（保证金模式/FX 暂停行为/风控阈值）直接影响全局交易口径
            "margin-mode-config:edit",
            "risk-threshold:edit",
            "fx:pause-behavior:edit"
    );

    /** 高风险等级常量。 */
    public static final String LEVEL_HIGH_RISK = "HIGH_RISK";
    /** 普通等级常量。 */
    public static final String LEVEL_LOW = "LOW";

    private HighRiskPermissionRegistry() {
    }

    /**
     * 解析权限码的风险等级。
     *
     * @param permissionCode 权限码
     * @return {@code HIGH_RISK} 或 {@code LOW}
     */
    public static String resolveRiskLevel(String permissionCode) {
        return CODES.contains(permissionCode) ? LEVEL_HIGH_RISK : LEVEL_LOW;
    }
}
