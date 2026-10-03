export const INITIAL_VISIBLE_BARS = 72;
export const RIGHT_OFFSET_BARS = 8;

export function resolveInitialVisibleLogicalRange(dataLength: number) {
  const safeLength = Math.max(Math.floor(dataLength), 1);
  return {
    from: safeLength - INITIAL_VISIBLE_BARS,
    to: safeLength + RIGHT_OFFSET_BARS
  };
}
