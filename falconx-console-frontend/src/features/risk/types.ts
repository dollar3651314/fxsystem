export type SnowflakeId = string;

export type RiskActionType = "REJECT_OPEN" | "REDUCE_ONLY" | "SUSPEND_SYMBOL" | "GLOBAL_PAUSE";
export type RiskTriggerSource = "AUTO" | "AUTO_CONCENTRATION" | "MANUAL" | "MANUAL_ADMIN";

export interface RiskActionItem {
  id: SnowflakeId;
  symbol: string | null;
  actionType: RiskActionType;
  triggerSource: RiskTriggerSource;
  triggerReason: string | null;
  hedgeLogId: SnowflakeId | null;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface RiskActionListResponse {
  items: RiskActionItem[];
  total: number;
  page: number;
  size: number;
}

export interface RiskActionListQuery {
  symbol?: string;
  actionType?: RiskActionType;
  triggerSource?: RiskTriggerSource;
  isActive?: boolean;
  page?: number;
  size?: number;
}

export interface RiskConfigItem {
  id: SnowflakeId;
  symbol: string;
  marketCode: string | null;
  maxPositionPerUser: string;
  maxPositionTotal: string;
  maintenanceMarginRate: string;
  maxLeverage: number;
  hedgeThresholdUsd: string;
  createdAt: string;
  updatedAt: string;
}

export interface RiskConfigListResponse {
  items: RiskConfigItem[];
  total: number;
  page: number;
  size: number;
}

export interface RiskConfigListQuery {
  symbol?: string;
  marketCode?: string;
  page?: number;
  size?: number;
}

export interface RiskMarketConfigItem {
  marketCode: string;
  concentrationThresholdUsd: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface RiskMarketConfigListResponse {
  items: RiskMarketConfigItem[];
}

export interface RiskActionActivateRequest {
  symbol?: string;
  actionType: RiskActionType;
  reason: string;
}

export interface RiskConfigCreateRequest {
  symbol: string;
  marketCode?: string;
  maxPositionPerUser: string;
  maxPositionTotal: string;
  maintenanceMarginRate: string;
  maxLeverage: number;
  hedgeThresholdUsd: string;
  reason: string;
}

export interface RiskConfigUpdateRequest {
  maxPositionPerUser: string;
  maxPositionTotal: string;
  maxLeverage: number;
  hedgeThresholdUsd: string;
  reason: string;
}

export interface RiskMarketConfigUpdateRequest {
  concentrationThresholdUsd: string;
  isEnabled: boolean;
  reason: string;
}

// ─── BBOOK-RISK-CONTROL-01 ──────────────────────────────────

export interface PlatformRiskConfigUpdateRequest {
  hedgeThresholdUsd: string;
  reason: string;
}

export interface DirectionImbalanceUpdateRequest {
  ratioThreshold?: string | null;
  minTotalUsd?: string | null;
  reason: string;
}

export interface UserRiskThresholdItem {
  userId: number;
  netExposureThresholdUsd: string | null;
  profitableNetExposureThresholdUsd: string | null;
  profitableUser: boolean;
  updatedBy: string | null;
  updatedReason: string | null;
  updatedAt: string | null;
  /** 后端跨 schema enrich：用户对外短号 / 邮箱 / 姓名（可能为 null）。 */
  userUid?: string | null;
  userEmail?: string | null;
  userFullName?: string | null;
}

export interface UserRiskThresholdListResponse {
  items: UserRiskThresholdItem[];
  total: number;
  page: number;
  size: number;
}

export interface UserRiskThresholdUpsertRequest {
  userId: number;
  netExposureThresholdUsd?: string | null;
  profitableNetExposureThresholdUsd?: string | null;
  profitableUser: boolean;
  reason: string;
}
