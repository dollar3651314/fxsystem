import type { TerminalView } from "./TerminalNav";

const TERMINAL_HASH_PREFIX = "#terminal/";
const TERMINAL_VIEWS: readonly TerminalView[] = [
  "dashboard",
  "market",
  "activity",
  "wallet",
  "settings"
];

export function resolveTerminalViewFromHash(hash: string): TerminalView | null {
  if (!hash.startsWith(TERMINAL_HASH_PREFIX)) {
    return null;
  }

  const value = hash.slice(TERMINAL_HASH_PREFIX.length);
  return isTerminalView(value) ? value : null;
}

export function terminalViewToHash(view: TerminalView): string {
  return `${TERMINAL_HASH_PREFIX}${view}`;
}

function isTerminalView(value: string): value is TerminalView {
  return TERMINAL_VIEWS.includes(value as TerminalView);
}
