/**
 * STAGE-8-NOTIFICATION Phase 4 管理端通知 + 模板类型，对齐管理端接口规范 §12。
 *
 * 雪花 ID（id / userId / relatedId）后端通过 String 字段序列化，前端必须以 string 接收（FX-071）。
 */

export type SnowflakeId = string;

export type NotificationLevel = "INFO" | "WARN" | "CRITICAL";

export type NotificationChannel = "IN_APP" | "EMAIL" | "TELEGRAM";

export type NotificationStatus = "UNREAD" | "READ";

// ---------- 模板 ----------

export interface AdminNotificationTemplateItem {
  code: string;
  titleTemplate: string;
  bodyTemplate: string;
  level: NotificationLevel;
  channels: NotificationChannel[];
  description: string | null;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface AdminNotificationTemplateListResponse {
  page: number;
  pageSize: number;
  total: number;
  items: AdminNotificationTemplateItem[];
}

export interface AdminNotificationTemplateListQuery {
  enabled?: 0 | 1;
  level?: 1 | 2 | 3;
  page?: number;
  size?: number;
}

export interface AdminNotificationTemplateUpsertRequest {
  code: string;
  titleTemplate: string;
  bodyTemplate: string;
  level: NotificationLevel;
  channels: NotificationChannel[];
  description?: string;
  enabled: boolean;
}

// ---------- 用户通知 ----------

export interface AdminNotificationItem {
  id: SnowflakeId;
  userId: SnowflakeId;
  type: string;
  templateCode: string | null;
  level: NotificationLevel;
  title: string;
  body: string;
  relatedKey: string | null;
  relatedId: SnowflakeId | null;
  payloadJson: string | null;
  status: NotificationStatus;
  readAt: string | null;
  createdAt: string;
  /** 后端跨 schema enrich：用户对外短号 / 邮箱 / 姓名（可能为 null）。 */
  userUid?: string | null;
  userEmail?: string | null;
  userFullName?: string | null;
}

export interface AdminNotificationListResponse {
  page: number;
  pageSize: number;
  total: number;
  items: AdminNotificationItem[];
}

export interface AdminNotificationListQuery {
  userId?: SnowflakeId;
  type?: string;
  templateCode?: string;
  level?: 1 | 2 | 3;
  status?: 0 | 1;
  fromCreatedAt?: string;
  toCreatedAt?: string;
  page?: number;
  size?: number;
}

export interface AdminNotificationSendRequest {
  userId: SnowflakeId;
  templateCode: string;
  params: Record<string, string>;
  reason: string;
}

// ---------- 展示元数据 ----------

export const LEVEL_META: Record<NotificationLevel, { color: string; label: string }> = {
  INFO: { color: "default", label: "INFO" },
  WARN: { color: "warning", label: "WARN" },
  CRITICAL: { color: "error", label: "CRITICAL" },
};

export const CHANNEL_META: Record<NotificationChannel, { color: string; label: string }> = {
  IN_APP: { color: "blue", label: "IN_APP" },
  EMAIL: { color: "cyan", label: "EMAIL" },
  TELEGRAM: { color: "geekblue", label: "TELEGRAM" },
};

export const STATUS_META: Record<NotificationStatus, { color: string; label: string }> = {
  UNREAD: { color: "processing", label: "UNREAD" },
  READ: { color: "default", label: "READ" },
};

/** 从模板 title/body 中提取所有 ${var} 变量名（去重，按出现顺序）。 */
export function extractPlaceholders(...templates: string[]): string[] {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const tpl of templates) {
    if (!tpl) continue;
    const matches = tpl.matchAll(/\$\{([a-zA-Z_][a-zA-Z0-9_]*)\}/g);
    for (const m of matches) {
      const name = m[1];
      if (!seen.has(name)) {
        seen.add(name);
        result.push(name);
      }
    }
  }
  return result;
}
