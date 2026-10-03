/**
 * STAGE-9-RISK-OPS-COMPLETE §12.2 管理端审计日志类型，对齐管理端接口规范 §13。
 *
 * 雪花 ID（id / adminUserId / targetId）后端通过 String 序列化，前端必须以 string 接收（FX-071）。
 */

export type SnowflakeId = string;

export type AuditRiskLevel = "LOW" | "MEDIUM" | "HIGH_RISK";

export interface AdminAuditLogItem {
  id: SnowflakeId;
  adminUserId: SnowflakeId;
  permissionCode: string;
  targetType: string | null;
  targetId: string | null;
  beforeValue: string | null;
  afterValue: string | null;
  riskLevel: AuditRiskLevel;
  ip: string | null;
  userAgent: string | null;
  occurredAt: string;
}

export interface AdminAuditLogListResponse {
  page: number;
  pageSize: number;
  total: number;
  items: AdminAuditLogItem[];
}

export interface AdminAuditLogListQuery {
  adminUserId?: SnowflakeId;
  permissionCode?: string;
  targetType?: string;
  targetId?: string;
  riskLevel?: AuditRiskLevel;
  fromOccurredAt?: string;
  toOccurredAt?: string;
  page?: number;
  size?: number;
}

export const RISK_LEVEL_META: Record<AuditRiskLevel, { color: string; label: string }> = {
  LOW: { color: "default", label: "LOW" },
  MEDIUM: { color: "warning", label: "MEDIUM" },
  HIGH_RISK: { color: "error", label: "HIGH_RISK" },
};
