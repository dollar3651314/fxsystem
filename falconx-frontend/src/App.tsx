import { useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { AuthGate } from "./features/auth/AuthGate";
import { useAuthStore } from "./features/auth/authStore";
import { useMarketStore } from "./features/market/marketStore";
import { TradingTerminal } from "./features/terminal/TradingTerminal";
import { AUTH_EXPIRED_EVENT, AUTH_EXPIRED_MESSAGE } from "./lib/authEvents";

export default function App() {
  const session = useAuthStore((state) => state.session);
  const clearSession = useAuthStore((state) => state.clearSession);
  const queryClient = useQueryClient();

  useEffect(() => {
    const handleAuthExpired = (event: Event) => {
      const message =
        event instanceof CustomEvent && typeof event.detail === "string"
          ? event.detail
          : AUTH_EXPIRED_MESSAGE;
      clearSession(message);
    };

    window.addEventListener(AUTH_EXPIRED_EVENT, handleAuthExpired);
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, handleAuthExpired);
  }, [clearSession]);

  useEffect(() => {
    if (session) {
      return;
    }

    queryClient.removeQueries({ queryKey: ["market"] });
    useMarketStore.getState().reset();
  }, [queryClient, session]);

  if (!session) {
    return <AuthGate />;
  }

  return <TradingTerminal />;
}
