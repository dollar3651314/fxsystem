import { useAdminAuthStore } from "./adminAuthStore";

/** 当前管理员是否拥有指定权限码。SUPER_ADMIN 对任意 code 返回 true (R6 FE-CONSOLE-022)。 */
export function useHasPermission(): (code: string) => boolean {
  const isSuperAdmin = useAdminAuthStore((s) => s.isSuperAdmin);
  const permissions = useAdminAuthStore((s) => s.permissions);
  return (code: string) => {
    if (isSuperAdmin) return true;
    return permissions.includes(code);
  };
}
