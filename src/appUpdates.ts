export const LATEST_RELEASE_URL =
  'https://github.com/23J1633/PhoneMIC/releases/latest';
const RELEASE_API =
  'https://api.github.com/repos/23J1633/PhoneMIC/releases/latest';

function parseVersion(value: string) {
  const match =
    /^v?(\d+)\.(\d+)(?:\.(\d+))?(?:-([\da-zA-Z.-]+))?(?:\+[\da-zA-Z.-]+)?$/.exec(
      value.trim(),
    );
  if (!match) throw new Error('Invalid release version');
  const parts = [match[1], match[2], match[3] || '0'].map(Number);
  if (!parts.every(Number.isSafeInteger))
    throw new Error('Invalid release version');
  return { parts, prerelease: match[4]?.split('.') || [] };
}

export function normalizeVersion(value: string): string {
  const { parts, prerelease } = parseVersion(value);
  return (
    parts.join('.') + (prerelease.length ? `-${prerelease.join('.')}` : '')
  );
}

export function compareVersions(left: string, right: string): number {
  const a = parseVersion(left),
    b = parseVersion(right);
  for (let i = 0; i < 3; i++) {
    if (a.parts[i] !== b.parts[i]) return Math.sign(a.parts[i] - b.parts[i]);
  }
  if (!a.prerelease.length || !b.prerelease.length)
    return Math.sign(b.prerelease.length - a.prerelease.length);
  for (let i = 0; i < Math.max(a.prerelease.length, b.prerelease.length); i++) {
    const x = a.prerelease[i],
      y = b.prerelease[i];
    if (x === undefined || y === undefined) return x === undefined ? -1 : 1;
    if (x === y) continue;
    const numericX = /^\d+$/.test(x),
      numericY = /^\d+$/.test(y);
    if (numericX && numericY) return Math.sign(Number(x) - Number(y));
    if (numericX !== numericY) return numericX ? -1 : 1;
    return x < y ? -1 : 1;
  }
  return 0;
}

export type UpdateResult =
  | { status: 'available' | 'current'; version: string }
  | { status: 'unpublished' };

export async function checkAppUpdate(
  current: string,
  signal: AbortSignal,
): Promise<UpdateResult> {
  const response = await fetch(RELEASE_API, {
    headers: { Accept: 'application/vnd.github+json' },
    signal,
  });
  if (response.status === 404) return { status: 'unpublished' };
  if (!response.ok) throw new Error('Release check unavailable');
  const release: { tag_name?: unknown; draft?: unknown; prerelease?: unknown } =
    await response.json();
  if (
    typeof release.tag_name !== 'string' ||
    release.draft ||
    release.prerelease
  )
    throw new Error('Invalid stable release');
  const version = normalizeVersion(release.tag_name);
  return {
    status: compareVersions(version, current) > 0 ? 'available' : 'current',
    version,
  };
}
