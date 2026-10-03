import { adminApi } from "../../lib/api/apiClient";

export interface AdminKycItem {
  submissionId: string;
  userId: string;
  level: number;
  status: "PENDING" | "APPROVED" | "REJECTED";
  idType: "ID_CARD" | "PASSPORT" | "DRIVER_LICENSE";
  idNumber: string;
  submittedAt: string;
  reviewerId: string | null;
  reviewAt: string | null;
  rejectReason: string | null;
  /** 后端跨 schema enrich：用户对外短号 / 邮箱 / 姓名（可能为 null）。 */
  userUid?: string | null;
  userEmail?: string | null;
  userFullName?: string | null;
}

export interface AdminKycDocumentItem {
  id: string;
  docType: "ID_FRONT" | "ID_BACK" | "HOLDING_SELFIE";
  mimeType: string;
  dataBase64: string;
  sha256: string;
}

export interface AdminKycDetailResponse {
  submission: AdminKycItem;
  documents: AdminKycDocumentItem[];
}

export interface AdminKycListResponse {
  page: number;
  pageSize: number;
  total: number;
  items: AdminKycItem[];
}

export interface AdminKycListQuery {
  status?: string;
  userId?: number;
  page?: number;
  size?: number;
}

function buildQuery(q: AdminKycListQuery): string {
  const params = new URLSearchParams();
  if (q.status) params.set("status", q.status);
  if (q.userId != null) params.set("userId", String(q.userId));
  params.set("page", String(q.page ?? 1));
  params.set("size", String(q.size ?? 20));
  return params.toString();
}

export const kycApi = {
  list: (q: AdminKycListQuery) =>
    adminApi.get<AdminKycListResponse>(`/admin/kyc?${buildQuery(q)}`),
  detail: (submissionId: string) =>
    adminApi.get<AdminKycDetailResponse>(`/admin/kyc/${submissionId}`),
  approve: (submissionId: string) =>
    adminApi.post<AdminKycItem>(`/admin/kyc/${submissionId}/approve`, {}),
  reject: (submissionId: string, reason: string) =>
    adminApi.post<AdminKycItem>(`/admin/kyc/${submissionId}/reject`, { reason }),
};
