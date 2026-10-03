/**
 * 解码 JWT payload。
 *
 * 仅做 base64url 解码 + JSON.parse，不做签名校验（前端拿不到公钥；
 * token 已经过 gateway/identity 验签）。仅用于读 sub/email/uid 等
 * 展示性字段。失败返回 null。
 */
export function decodeJwtPayload<T extends Record<string, unknown> = Record<string, unknown>>(
  token: string | null | undefined
): T | null {
  if (!token) return null;
  const parts = token.split(".");
  if (parts.length < 2) return null;
  try {
    const base64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
    const json = atob(padded);
    return JSON.parse(json) as T;
  } catch {
    return null;
  }
}
