// STAGE-14D3b Task7：FX_PAUSED 8 类目行为配置页类型。
//
// 字段口径对齐 console-service D3b Task3 契约（fx-pause-behavior GET/PUT）：
//   · category：标的类目编号 1-8（crypto/forex/metal/index/energy/stock/etf/other）。
//   · categoryName：后端返回的类目名（优先展示）；缺失/数字时回退 FX_CATEGORY_LABELS。
//   · allowOpen/allowClose/allowLiquidation：FX 报价暂停期间该类目的开仓/平仓/强平闸门。
//   · reason 为高危操作原因（update 必填），随 PUT body 上送，仅用于审计。

export interface FxPauseBehavior {
  category: number;
  categoryName: string;
  allowOpen: boolean;
  allowClose: boolean;
  allowLiquidation: boolean;
}

// 8 类目展示映射（缺失/后端只回数字时的回退 label，非 owner symbol 数据）。
export const FX_CATEGORY_LABELS: Record<number, string> = {
  1: "加密(crypto)",
  2: "外汇(forex)",
  3: "金属(metal)",
  4: "指数(index)",
  5: "能源(energy)",
  6: "股票(stock)",
  7: "ETF",
  8: "其他(other)",
};
