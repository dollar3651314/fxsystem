import { adminApi } from "../../lib/api/apiClient";
import type {
  AdminNotificationItem,
  AdminNotificationListQuery,
  AdminNotificationListResponse,
  AdminNotificationSendRequest,
  AdminNotificationTemplateItem,
  AdminNotificationTemplateListQuery,
  AdminNotificationTemplateListResponse,
  AdminNotificationTemplateUpsertRequest,
  SnowflakeId,
} from "./types";

function buildTemplateQuery(q: AdminNotificationTemplateListQuery): string {
  const params = new URLSearchParams();
  if (q.enabled !== undefined) params.set("enabled", String(q.enabled));
  if (q.level !== undefined) params.set("level", String(q.level));
  params.set("page", String(q.page ?? 1));
  params.set("size", String(q.size ?? 20));
  return params.toString();
}

function buildNotificationQuery(q: AdminNotificationListQuery): string {
  const params = new URLSearchParams();
  if (q.userId) params.set("userId", q.userId);
  if (q.type) params.set("type", q.type);
  if (q.templateCode) params.set("templateCode", q.templateCode);
  if (q.level !== undefined) params.set("level", String(q.level));
  if (q.status !== undefined) params.set("status", String(q.status));
  if (q.fromCreatedAt) params.set("fromCreatedAt", q.fromCreatedAt);
  if (q.toCreatedAt) params.set("toCreatedAt", q.toCreatedAt);
  params.set("page", String(q.page ?? 1));
  params.set("size", String(q.size ?? 20));
  return params.toString();
}

/**
 * STAGE-8-NOTIFICATION Phase 4 通知 + 模板 admin API client。
 *
 * 8 端点对齐管理端接口规范 §12：
 * - 5 模板 CRUD（list / detail / create / update / delete）
 * - 2 通知（list / detail）
 * - 1 手动发送（send）
 */
export const notificationApi = {
  // 模板 CRUD
  listTemplates: (q: AdminNotificationTemplateListQuery) =>
    adminApi.get<AdminNotificationTemplateListResponse>(
      `/admin/notification-templates?${buildTemplateQuery(q)}`,
    ),

  getTemplate: (code: string) =>
    adminApi.get<AdminNotificationTemplateItem>(`/admin/notification-templates/${code}`),

  createTemplate: (req: AdminNotificationTemplateUpsertRequest) =>
    adminApi.post<AdminNotificationTemplateItem>("/admin/notification-templates", req),

  updateTemplate: (code: string, req: AdminNotificationTemplateUpsertRequest) =>
    adminApi.put<AdminNotificationTemplateItem>(`/admin/notification-templates/${code}`, req),

  deleteTemplate: (code: string) =>
    adminApi.delete<void>(`/admin/notification-templates/${code}`),

  // 用户通知
  list: (q: AdminNotificationListQuery) =>
    adminApi.get<AdminNotificationListResponse>(`/admin/notifications?${buildNotificationQuery(q)}`),

  detail: (id: SnowflakeId) =>
    adminApi.get<AdminNotificationItem>(`/admin/notifications/${id}`),

  send: (req: AdminNotificationSendRequest) =>
    adminApi.post<AdminNotificationItem>("/admin/notifications/send", req),
};
