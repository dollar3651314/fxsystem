/** 阶段 2.1 客户管理类型，对齐管理端接口规范 §3。 */

// 雪花 ID 在 BBook 项目中远超 JS Number.MAX_SAFE_INTEGER (2^53-1)，
// 后端通过 @JsonSerialize(ToStringSerializer) 序列化为 string，前端必须以 string 接收（FX-071）
export type SnowflakeId = string;

export type CustomerStatus = "ACTIVE" | "FROZEN" | "BANNED" | "PENDING_DEPOSIT";

export interface CustomerListItem {
  userId: SnowflakeId;
  uid: string;
  email: string;
  status: CustomerStatus;
  emailVerified: boolean;
  groupCode: string;
  balanceUSD: string;
  lastLoginAt: string | null;
  createdAt: string;
  /** firstName + lastName 拼接结果，profile 未填时为 null */
  fullName: string | null;
  /** STAGE-6-KYC：0=未认证 / 1=已通过简单 KYC */
  kycLevel: number | null;
}

export interface CustomerListResponse {
  items: CustomerListItem[];
  total: number;
  page: number;
  size: number;
}

export interface CustomerListQuery {
  email?: string;
  status?: CustomerStatus[];
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export interface CustomerProfile {
  firstName: string;
  middleName: string | null;
  lastName: string;
  birthDate: string;          // ISO yyyy-MM-dd
  nationality: string;        // ISO 3166-1 alpha-3
  gender: 1 | 2 | 9 | null;
  residenceCountry: string | null;
  residenceState: string | null;
  residenceCity: string | null;
  residenceAddress: string | null;
  residencePostalCode: string | null;
  phoneCountryCode: string | null;
  phoneNumber: string | null;
  languagePreference: string;
  timezone: string;
  profileVerified: boolean;
}

export interface CustomerDetail {
  userId: SnowflakeId;
  uid: string;
  email: string;
  status: CustomerStatus;
  emailVerified: boolean;
  groupCode: string;
  activatedAt: string | null;
  lastLoginAt: string | null;
  lastLoginIp: string | null;
  balance: {
    totalUSD: string;
    availableUSD: string;
    marginUsedUSD: string;
  };
  createdAt: string;
  /** STAGE-6-KYC：0=未认证 / 1=已通过简单 KYC */
  kycLevel: number;
  /** STAGE-1B-USER-PROFILE：注册后写入；历史数据可能为 null。 */
  profile: CustomerProfile | null;
}

export interface FreezeUnfreezeResponse {
  userId: SnowflakeId;
  previousStatus: CustomerStatus;
  newStatus: CustomerStatus;
}

export interface BalanceAdjustResponse {
  userId: SnowflakeId;
  deltaUSD: string;
  balanceBefore: string;
  balanceAfter: string;
  ledgerEntryId: SnowflakeId;
}

/** PATCH /admin/customers/{userId} 请求体：四块独立可选，reason 必填 ≥ 10 字符。 */
export interface CustomerPatchRequest {
  identity?: {
    email?: string;
    emailVerified?: boolean;
    groupCode?: string;
    kycLevel?: number;
    status?: CustomerStatus;
  };
  profile?: Partial<CustomerProfile>;
  reason: string;
}
