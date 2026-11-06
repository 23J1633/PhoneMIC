import { useCallback, useEffect, useRef, useState } from 'react';
import { Native } from './native';
import {
  checkAppUpdate,
  normalizeVersion,
  type UpdateResult,
} from './appUpdates';

export function useAppUpdate() {
  const currentVersion = normalizeVersion(Native.appVersion);
  const [result, setResult] = useState<
    UpdateResult | { status: 'checking' | 'failed' }
  >({ status: 'checking' });
  const pending = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const cancel = useCallback(() => {
    generation.current++;
    pending.current?.abort();
  }, []);
  const check = useCallback(async () => {
    const current = ++generation.current;
    pending.current?.abort();
    const controller = new AbortController();
    pending.current = controller;
    setResult({ status: 'checking' });
    const timer = setTimeout(() => controller.abort(), 8000);
    try {
      const next = await checkAppUpdate(currentVersion, controller.signal);
      if (generation.current === current) setResult(next);
    } catch {
      if (generation.current === current) setResult({ status: 'failed' });
    } finally {
      clearTimeout(timer);
      if (generation.current === current) pending.current = null;
    }
  }, [currentVersion]);
  useEffect(() => {
    void check();
    return cancel;
  }, [check, cancel]);
  return { currentVersion, result, check };
}
