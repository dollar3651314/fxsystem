/** 管理端鉴权相关 TypeScript 类型，对齐 docs/api/管理端接口规范.md §2 字段。 */

export interface AdminAuthTokenResponse {
  adminUserId: number;
  username: string;
  realName: string | null;
  roles: string[];
  mustChangePassword: boolean;
  accessToken: string;
  refreshToken: string;
  accessTokenExpiresIn: number;
  refreshTokenExpiresIn: number;
}

export interface AdminMeRoleSummary {
  code: string;
  name: string;
}

export interface AdminMeResponse {
  adminUserId: number;
  username: string;
  realName: string | null;
  roles: AdminMeRoleSummary[];
  mustChangePassword: boolean;
  lastLoginAt: string | null;
  lastLoginIp: string | null;
}

export interface AdminMePermissionsResponse {
  permissions: string[];
  isSuperAdmin: boolean;
}

export interface AdminMeMenuNode {
  code: string;
  name: string;
  icon: string | null;
  path: string | null;
  permissionCode: string | null;
  children: AdminMeMenuNode[];
}

export interface AdminMeMenusResponse {
  menus: AdminMeMenuNode[];
}
