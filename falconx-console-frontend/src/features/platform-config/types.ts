// STAGE-14D3b Task5：保证金模式冷静期配置页类型。
//
// 字段口径对齐 console-service D3b Task2 契约（margin-mode-config GET/PUT）：
//   · coolingPeriodSeconds：margin mode 切换冷静期秒数；admin 可配 60-604800（60s ~ 7d）。
//   · 越界由 trading-core 裁决拒绝，console 翻 90950；前端仅做即时范围提示，不替代后端裁决。
//   · reason 为高危操作原因（update 必填），随 PUT body 上送，仅用于审计。

export interface MarginModeConfig {
  coolingPeriodSeconds: number;
}

// STAGE-14D3b Task6：StopOut/MarginCall 阈值配置页类型。
//
// 字段口径对齐 console-service D3b Task2 契约（risk-thresholds GET/PUT）：
//   · stopOutLevel：强平保证金率阈值；admin 可配 0.05-0.95。
//   · marginCallLevel：追保通知保证金率阈值；admin 可配 0.50-2.00。
//   · 越界由 trading-core 裁决拒绝，console 翻 90952；前端仅做即时范围提示，不替代后端裁决。
//   · 用 string 承载阈值以保精度（避免浮点）；GET 若返回 number 则读时 String() 兼容。
//   · reason 为高危操作原因（update 必填），随 PUT body 上送，仅用于审计。
export interface RiskThreshold {
  stopOutLevel: string;
  marginCallLevel: string;
}
