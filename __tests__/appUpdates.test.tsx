import React from 'react';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';
import { Linking, Text } from 'react-native';
import {
  checkAppUpdate,
  compareVersions,
  normalizeVersion,
  LATEST_RELEASE_URL,
} from '../src/appUpdates';
import { useAppUpdate } from '../src/useAppUpdate';
import { UpdateSetting } from '../src/UpdateSetting';
import { translator } from '../src/i18n';
import { usePalette } from '../src/theme';
import type { PhoneMicController } from '../src/usePhoneMic';

jest.mock('../src/native', () => ({ Native: { appVersion: '1.0' } }));

const originalFetch = globalThis.fetch;
const release = (tag_name: string) =>
  ({
    ok: true,
    status: 200,
    json: async () => ({ tag_name, draft: false, prerelease: false }),
  } as Response);

describe('release version comparisons', () => {
  it('normalizes installed versions and compares numeric versions correctly', () => {
    expect(normalizeVersion('1.0')).toBe('1.0.0');
    expect(compareVersions('v1.0.0', '1.0')).toBe(0);
    expect(compareVersions('1.10.0', '1.9.9')).toBe(1);
    expect(compareVersions('1.0.0', '1.0.1')).toBe(-1);
    expect(compareVersions('1.1.0', '1.1.0-rc.1')).toBe(1);
    expect(compareVersions('1.1.0-rc.10', '1.1.0-rc.2')).toBe(1);
    expect(() => normalizeVersion('latest')).toThrow();
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });
  it('reports an unpublished repository separately from a network failure', async () => {
    globalThis.fetch = jest.fn().mockResolvedValue({ status: 404 });
    await expect(
      checkAppUpdate('1.0.0', new AbortController().signal),
    ).resolves.toEqual({ status: 'unpublished' });
  });
  it('does not mistake a rate limit or an invalid release for being up to date', async () => {
    globalThis.fetch = jest.fn().mockResolvedValue({ status: 403, ok: false });
    await expect(
      checkAppUpdate('1.0.0', new AbortController().signal),
    ).rejects.toThrow();
    globalThis.fetch = jest.fn().mockResolvedValue({
      status: 200,
      ok: true,
      json: async () => ({ tag_name: 'v2.0.0', draft: true }),
    });
    await expect(
      checkAppUpdate('1.0.0', new AbortController().signal),
    ).rejects.toThrow();
  });
});

describe('automatic settings update checks', () => {
  let root: ReactTestRenderer | undefined;
  let update: ReturnType<typeof useAppUpdate>;
  function Harness() {
    update = useAppUpdate();
    return null;
  }
  beforeEach(() => {
    jest.useFakeTimers();
    globalThis.fetch = jest.fn().mockResolvedValue(release('v1.0.0'));
  });
  afterEach(async () => {
    if (root) await act(async () => root!.unmount());
    root = undefined;
    globalThis.fetch = originalFetch;
    jest.restoreAllMocks();
    jest.useRealTimers();
  });
  it('automatically checks on entry using the installed Android version', async () => {
    await act(async () => {
      root = create(<Harness />);
    });
    expect(globalThis.fetch).toHaveBeenCalledTimes(1);
    expect(update.currentVersion).toBe('1.0.0');
    expect(update.result).toEqual({ status: 'current', version: '1.0.0' });
  });
  it('reports a newer version and allows manual retries after failure', async () => {
    jest
      .mocked(globalThis.fetch)
      .mockRejectedValueOnce(new Error('Network unavailable'))
      .mockResolvedValueOnce(release('v1.0.1'));
    await act(async () => {
      root = create(<Harness />);
    });
    expect(update.result.status).toBe('failed');
    await act(async () => {
      await update.check();
    });
    expect(update.result).toEqual({ status: 'available', version: '1.0.1' });
  });
  it('stops a stalled check after eight seconds', async () => {
    globalThis.fetch = jest.fn().mockImplementation(
      (_url, options) =>
        new Promise((_resolve, reject) => {
          options.signal.addEventListener('abort', () =>
            reject(new Error('Aborted')),
          );
        }),
    );
    await act(async () => {
      root = create(<Harness />);
    });
    expect(update.result.status).toBe('checking');
    await act(async () => {
      jest.advanceTimersByTime(8000);
    });
    expect(update.result.status).toBe('failed');
  });
  it('cancels the request when leaving settings', async () => {
    globalThis.fetch = jest.fn().mockImplementation(
      (_url, options) =>
        new Promise((_resolve, reject) => {
          options.signal.addEventListener('abort', () =>
            reject(new Error('Aborted')),
          );
        }),
    );
    await act(async () => {
      root = create(<Harness />);
    });
    const signal = jest.mocked(globalThis.fetch).mock.calls[0][1]!.signal!;
    await act(async () => root!.unmount());
    root = undefined;
    expect(signal.aborted).toBe(true);
  });
  it('shows the result and opens the repository latest-release page', async () => {
    const open = jest.spyOn(Linking, 'openURL').mockResolvedValue(true);
    const c = {
      t: translator('zh'),
      showNotice: jest.fn(),
    } as unknown as PhoneMicController;
    function Footer() {
      return <UpdateSetting c={c} p={usePalette('light')} />;
    }
    await act(async () => {
      root = create(<Footer />);
    });
    expect(
      root!.root
        .findAllByType(Text)
        .some(node => node.props.children === '已是最新版本'),
    ).toBe(true);
    const link = root!.root
      .findAllByProps({ accessibilityRole: 'link' })
      .find(node => typeof node.props.onPress === 'function')!;
    await act(async () => {
      link.props.onPress();
    });
    expect(open).toHaveBeenCalledWith(LATEST_RELEASE_URL);
  });
});
