import type { ReactNode } from "react";
import { useHasPermission } from "../lib/auth/usePermission";

interface RequiresPermissionProps {
  /** 权限码，如 "customer:freeze"。 */
  code: string;
  children: ReactNode;
}

/** 按权限码渲染 children；无权限完全隐藏 (不是 disabled，按 DESIGN §11 / R6 FE-CONSOLE-027)。 */
export function RequiresPermission({ code, children }: RequiresPermissionProps) {
  const hasPermission = useHasPermission();
  if (!hasPermission(code)) return null;
  return <>{children}</>;
}
