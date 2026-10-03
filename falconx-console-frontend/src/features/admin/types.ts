/** 阶段 1 P2-P5 RBAC 自管类型，对齐管理端接口规范 §5. */

// 雪花 ID 必须以 string 接收（FX-071，与 customer 模块共用语义但独立别名命名空间）
export type SnowflakeId = string;

// =========== P2 admin-users ===========

export type AdminUserStatus = "ACTIVE" | "DISABLED";

export interface AdminUserRoleRef {
  id: SnowflakeId;
  code: string;
  name: string;
}

export interface AdminUserListItem {
  id: SnowflakeId;
  username: string;
  realName: string | null;
  status: AdminUserStatus;
  mustChangePassword: boolean;
  lastLoginAt: string | null;
  lastLoginIp: string | null;
  createdAt: string;
  roles: AdminUserRoleRef[];
}

export interface AdminUserListResponse {
  items: AdminUserListItem[];
  total: number;
  page: number;
  size: number;
}

export interface AdminUserListQuery {
  username?: string;
  realName?: string;
  status?: AdminUserStatus;
  roleCode?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export interface AdminUserCreateRequest {
  username: string;
  realName?: string;
  roleIds: SnowflakeId[];
  password?: string | null;
  mustChangePassword?: boolean;
}

export interface AdminUserCreateResponse {
  id: SnowflakeId;
  username: string;
  /** 仅 password=null 时返回（本次响应可见，刷新后丢失）。 */
  generatedPassword: string | null;
}

export interface AdminUserUpdateRequest {
  realName?: string;
  roleIds: SnowflakeId[];
}

export interface AdminUserResetPasswordRequest {
  password?: string | null;
  forceChangePassword?: boolean;
  reason: string;
}

export interface AdminUserResetPasswordResponse {
  id: SnowflakeId;
  generatedPassword: string | null;
}

// =========== P3 admin-roles ===========

export interface AdminRoleListItem {
  id: SnowflakeId;
  code: string;
  name: string;
  description: string | null;
  isSystem: boolean;
  memberCount: number;
  permissionCount: number;
  createdAt: string;
}

export interface AdminRoleListResponse {
  items: AdminRoleListItem[];
  total: number;
  page: number;
  size: number;
}

export interface AdminRoleListQuery {
  code?: string;
  name?: string;
  isSystem?: boolean;
  page?: number;
  size?: number;
}

export interface AdminRoleDetail {
  id: SnowflakeId;
  code: string;
  name: string;
  description: string | null;
  isSystem: boolean;
  createdAt: string;
}

export interface AdminRoleCreateRequest {
  code: string;
  name: string;
  description?: string;
}

export interface AdminRoleUpdateRequest {
  name: string;
  description?: string;
}

export interface AdminRolePermissionsResponse {
  permissionCodes: string[];
}

export interface AdminRoleAssignPermissionsRequest {
  permissionCodes: string[];
  reason: string;
}

// =========== P4 admin-menus ===========

export interface AdminMenuItem {
  id: SnowflakeId;
  parentId: SnowflakeId | null;
  code: string;
  name: string;
  icon: string | null;
  path: string | null;
  permissionCode: string | null;
  sortOrder: number;
  isVisible: boolean;
  children: AdminMenuItem[];
}

export interface AdminMenuListResponse {
  items: AdminMenuItem[];
}

export interface AdminMenuCreateRequest {
  parentId: SnowflakeId | null;
  code: string;
  name: string;
  icon?: string | null;
  path?: string | null;
  permissionCode: string;
  sortOrder: number;
  isVisible: boolean;
}

export type AdminMenuUpdateRequest = AdminMenuCreateRequest;

export type AdminMenuSortDirection = "up" | "down";

// =========== P5 admin-permissions ===========

export interface AdminPermissionListItem {
  code: string;
  module: string;
  action: string;
  description: string | null;
  isHighRisk: boolean;
  roleCount: number;
  createdAt: string;
}

export interface AdminPermissionListResponse {
  items: AdminPermissionListItem[];
  total: number;
  page: number;
  size: number;
}

export interface AdminPermissionListQuery {
  module?: string;
  action?: string;
  highRiskOnly?: boolean;
  page?: number;
  size?: number;
}

export interface AdminPermissionRoleRef {
  roleId: SnowflakeId;
  roleCode: string;
  roleName: string;
}
