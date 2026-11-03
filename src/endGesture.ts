export type EndGestureAction = 'guide' | 'send' | 'clear';

export const endTargets = [
  { action: 'guide', x: -30, y: -54 },
  { action: 'clear', x: 48, y: -114 },
  { action: 'send', x: 126, y: -54 },
] as const;

export function endActionAt(x: number, y: number): EndGestureAction | null {
  return (
    endTargets.find(target => Math.hypot(x - target.x, y - target.y) <= 43)
      ?.action ?? null
  );
}
