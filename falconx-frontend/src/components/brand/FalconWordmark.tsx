type FalconWordmarkProps = {
  compact?: boolean;
};

export function FalconWordmark({ compact = false }: FalconWordmarkProps) {
  return <span className={compact ? "falcon-wordmark compact" : "falcon-wordmark"}>FalconX</span>;
}
