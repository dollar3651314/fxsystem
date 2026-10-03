import { Link } from "react-router-dom";

/**
 * 管理端通用「用户单元格」——在各关联数据列表/详情里统一展示用户姓名 + 邮箱，
 * 并以姓名（缺失时回退 UID / userId）作为可点击入口跳转客户详情页。
 *
 * 字段来源：后端各 admin DTO 跨 schema enrich 的 userUid / userEmail / userFullName
 * （见 console AdminUserInfoEnricher）。三者可能为 null（用户不存在或未 enrich），各自降级显示「—」。
 */
export interface UserCellProps {
  /** 用户雪花 ID（用于跳转 /admin/customers/{userId}）。number | string | null。 */
  userId?: string | number | null;
  /** 对外短号 t_user.uid。 */
  uid?: string | null;
  /** 邮箱。 */
  email?: string | null;
  /** 姓名（firstName + lastName）。 */
  fullName?: string | null;
  /** 紧凑模式（详情区单行展示用，默认 false=两行表格单元）。 */
  inline?: boolean;
}

export function UserCell({ userId, uid, email, fullName, inline = false }: UserCellProps) {
  const hasUser = userId !== null && userId !== undefined && String(userId).length > 0;
  // 主标签：优先姓名，其次 UID，最后 userId 原值
  const primary = fullName ?? uid ?? (hasUser ? String(userId) : "—");
  const primaryNode = hasUser ? (
    <Link to={`/admin/customers/${userId}`} title={`查看客户详情 · UID ${uid ?? "—"}`}>
      {primary}
    </Link>
  ) : (
    <span>{primary}</span>
  );

  if (inline) {
    return (
      <span>
        {primaryNode}
        {email ? <span style={{ color: "var(--fx-muted)" }}> · {email}</span> : null}
      </span>
    );
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", lineHeight: 1.35 }}>
      {primaryNode}
      <span style={{ fontSize: 12, color: "var(--fx-muted)" }}>{email ?? "—"}</span>
    </div>
  );
}
