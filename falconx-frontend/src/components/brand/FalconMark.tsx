type FalconMarkProps = {
  className?: string;
  title?: string;
};

export function FalconMark({ className, title = "FalconX" }: FalconMarkProps) {
  return (
    <svg
      className={className}
      viewBox="0 0 160 96"
      role="img"
      aria-label={title}
      xmlns="http://www.w3.org/2000/svg"
    >
      <path d="M80 39 10 8l28 45-24 35 66-28 66 28-24-35 28-45-70 31Z" fill="currentColor" />
      <path d="M80 39 52 62h56L80 39Z" fill="rgba(255,255,255,.2)" />
      <path d="M80 52 66 75h28L80 52Z" fill="currentColor" opacity=".88" />
    </svg>
  );
}
