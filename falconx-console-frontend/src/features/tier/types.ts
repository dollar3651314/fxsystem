// STAGE-14C2 Task 9 R10：杠杆/MM 档位（tier）配置页类型。
//
// 字段口径对齐 console-service Task 8 契约（AdminTierListResponse.Item / AdminTierCreateRequest /
// AdminTierUpdateRequest）：
//   · 区间 [notionalLower, notionalUpper)（下界含、上界不含）；notionalUpper=null 表示无上限。
//   · 金额类（notionalLower/notionalUpper/mmRate）后端为 BigDecimal，前端用 string 承载避免精度丢失。
//   · 业务校验（区间不重叠 / maxLeverage×mmRate≤1.0 / tierNo 唯一）由 trading-core 执行
//     （错误码 90930 not found / 90931 校验失败 / 90932 区间重叠，后端已翻译可读消息）；
//     前端仅做即时提示（maxLeverage×mmRate≤1.0、notionalLower<notionalUpper），不替代后端裁决。
//   · reason 为高危操作原因（create/update/delete 必填），仅用于管理端审计，不透传到 trading 业务。

export type SnowflakeId = string;

/** 单档位视图（对齐 AdminTierListResponse.Item）。 */
export interface TierItem {
  id: SnowflakeId;
  symbol: string;
  groupCode: string;
  tierNo: number;
  notionalLower: string;
  /** null 表示无上限（最高档）。 */
  notionalUpper: string | null;
  maxLeverage: number;
  mmRate: string;
  enabled: boolean;
}

/** 分页列表响应（对齐 AdminTierListResponse）。 */
export interface TierListResponse {
  items: TierItem[];
  total: number;
  page: number;
  size: number;
}

export interface TierListQuery {
  symbol?: string;
  groupCode?: string;
  page?: number;
  size?: number;
}

/** 新建请求（对齐 AdminTierCreateRequest）。 */
export interface TierCreateRequest {
  symbol: string;
  groupCode: string;
  tierNo: number;
  notionalLower: string;
  notionalUpper?: string | null;
  maxLeverage: number;
  mmRate: string;
  reason: string;
}

/** 编辑请求（对齐 AdminTierUpdateRequest，symbol/groupCode 落档键不可改）。 */
export interface TierUpdateRequest {
  tierNo: number;
  notionalLower: string;
  notionalUpper?: string | null;
  maxLeverage: number;
  mmRate: string;
  reason: string;
}
