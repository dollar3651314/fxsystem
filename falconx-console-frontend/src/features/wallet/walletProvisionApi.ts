import { adminApi } from "../../lib/api/apiClient";

export interface AdminWalletProvisionDlqItem {
  id: string;
  eventId: string;
  userId: string;
  uid: string | null;
  email: string | null;
  attemptCount: number;
  status: "PENDING" | "RESOLVED";
  lastErrorCode: string | null;
  lastErrorMessage: string | null;
  lastAttemptAt: string;
  resolvedAt: string | null;
  createdAt: string;
}

export interface AdminWalletProvisionDlqListResponse {
  page: number;
  pageSize: number;
  total: number;
  items: AdminWalletProvisionDlqItem[];
}

export interface AdminWalletProvisionDlqListQuery {
  status?: number; // 0=PENDING / 1=RESOLVED
  userId?: number;
  page?: number;
  size?: number;
}

function buildQuery(q: AdminWalletProvisionDlqListQuery): string {
  const params = new URLSearchParams();
  if (q.status != null) params.set("status", String(q.status));
  if (q.userId != null) params.set("userId", String(q.userId));
  params.set("page", String(q.page ?? 1));
  params.set("size", String(q.size ?? 20));
  return params.toString();
}

export const walletProvisionApi = {
  list: (q: AdminWalletProvisionDlqListQuery) =>
    adminApi.get<AdminWalletProvisionDlqListResponse>(`/admin/wallet/provision-dlq?${buildQuery(q)}`),
  retry: (id: string, reason: string) =>
    adminApi.post<AdminWalletProvisionDlqItem>(`/admin/wallet/provision-dlq/${id}/retry`, { reason }),
};
