export const AUTH_EXPIRED_EVENT = "falconx:auth-expired";
export const AUTH_EXPIRED_MESSAGE = "登录状态已过期，请重新登录。";

export function notifyAuthExpired(message = AUTH_EXPIRED_MESSAGE): void {
  if (typeof window === "undefined") {
    return;
  }

  window.dispatchEvent(
    new CustomEvent<string>(AUTH_EXPIRED_EVENT, {
      detail: message
    })
  );
}
