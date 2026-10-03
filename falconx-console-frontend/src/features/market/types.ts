// STAGE-14E2 Task4：console FX rate 监控页类型。
//
// 字段口径对齐 console-service E2 Task3 契约（GET /admin/market/fx/rates，透传 market FX RPC）：
//   · baseCurrency / quoteCurrency：货币对（展示为 base/quote）。
//   · rate：汇率，用 string 承载以保精度（不转 number）。
//   · eventTimeMillis：报价事件时间（epoch ms），前端依此与当前时间差判 stale。
//   · sourceLpCode / sourceSymbol：报价来源 LP 代码与来源标的。
// 后端无 stale 布尔字段——stale 由前端依 eventTimeMillis 判定（见 STALE_THRESHOLD_MS）。

export interface FxRate {
  baseCurrency: string;
  quoteCurrency: string;
  rate: string;
  eventTimeMillis: number;
  sourceLpCode: string;
  sourceSymbol: string;
}
