import { adminApi } from "../../lib/api/apiClient";
import type {
  SystemConfigAuditResponse,
  SystemConfigItem,
  SystemConfigListResponse,
  SystemConfigUpdateRequest,
} from "./types";

export const systemConfigApi = {
  list: (category?: string) =>
    adminApi.get<SystemConfigListResponse>(
      category ? `/admin/system-config?category=${category}` : `/admin/system-config`
    ),

  get: (configKey: string) =>
    adminApi.get<SystemConfigItem>(`/admin/system-config/${encodeURIComponent(configKey)}`),

  update: (configKey: string, req: SystemConfigUpdateRequest) =>
    adminApi.put<SystemConfigItem>(
      `/admin/system-config/${encodeURIComponent(configKey)}`,
      req
    ),

  reset: (configKey: string) =>
    adminApi.post<SystemConfigItem>(
      `/admin/system-config/${encodeURIComponent(configKey)}/reset`,
      {}
    ),

  history: (configKey: string, limit = 50) =>
    adminApi.get<SystemConfigAuditResponse>(
      `/admin/system-config/${encodeURIComponent(configKey)}/history?limit=${limit}`
    ),
};
