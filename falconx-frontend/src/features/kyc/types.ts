export type KycStatus = "PENDING" | "APPROVED" | "REJECTED";

export type KycIdType = "ID_CARD" | "PASSPORT" | "DRIVER_LICENSE";

export interface KycSubmissionResponse {
  /**
   * 当前 t_user.kyc_level（权限源）。
   * 0 = 未认证，≥1 = 已认证。前端应优先看此值判断「已认证」，
   * 而不是看 submission status（admin 可直接 patch kyc_level 而不动 submission）。
   */
  currentKycLevel: number;
  submissionId: string | null;
  userId: string;
  level: number;
  /** submission 审核状态，无 submission 时 null */
  status: KycStatus | null;
  idType: KycIdType | null;
  idNumber: string | null;
  submittedAt: string | null;
  reviewAt: string | null;
  rejectReason: string | null;
}

export interface SubmitKycCommand {
  idType: KycIdType;
  idNumber: string;
  idFrontBase64: string;
  idFrontMimeType?: string;
  idBackBase64: string;
  idBackMimeType?: string;
  selfieBase64: string;
  selfieMimeType?: string;
}
