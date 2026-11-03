import React from 'react';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';
import { Modal, Text } from 'react-native';
import { VoiceprintScreen } from '../src/VoiceprintScreen';
import { ActionButton, IconButton } from '../src/components';
import { events } from '../src/native';
import { usePalette } from '../src/theme';
import {
  defaultSettings,
  VOICEPRINT_MODEL,
  type VoiceProfile,
} from '../src/types';
import type { PhoneMicController } from '../src/usePhoneMic';

jest.mock('react-native-safe-area-context', () => ({
  SafeAreaView: require('react-native').View,
}));
jest.mock('../src/native', () => {
  const listeners = new Set<(mockEvent: unknown) => void>();
  return {
    events: {
      addListener: (_: string, mockListener: (mockEvent: unknown) => void) => {
        listeners.add(mockListener);
        return { remove: () => listeners.delete(mockListener) };
      },
      emit: (mockEvent: unknown) =>
        listeners.forEach(mockListener => mockListener(mockEvent)),
    },
  };
});

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
};
const profile: VoiceProfile = {
  model: VOICEPRINT_MODEL,
  embedding: [1, 0],
  createdAt: 1,
  durationMs: 10000,
};

describe('voiceprint enrollment controls', () => {
  let root: ReactTestRenderer;
  let c: PhoneMicController;
  let close: jest.Mock;
  const progress = (value: number) =>
    (events as unknown as { emit: (event: unknown) => void }).emit({
      progress: value,
      level: 0.8,
    });
  const button = () => root.root.findByType(ActionButton);
  const back = () => root.root.findByType(IconButton);
  const hasSavedMessage = () =>
    root.root
      .findAllByType(Text)
      .some(node => node.props.children === 'voiceprintSaved');
  function Harness() {
    return <VoiceprintScreen c={c} p={usePalette('light')} close={close} />;
  }
  beforeEach(async () => {
    close = jest.fn();
    c = {
      settings: defaultSettings,
      t: (key: string) => key,
      voiceProfile: null,
      voiceProfileIncompatible: false,
      enrollVoiceprint: jest.fn(),
      cancelVoiceprint: jest.fn().mockResolvedValue(undefined),
      saveVoiceprint: jest.fn().mockResolvedValue(undefined),
      updateSettings: jest.fn(),
      deleteVoiceprint: jest.fn().mockResolvedValue(undefined),
    } as unknown as PhoneMicController;
    await act(async () => {
      root = create(<Harness />);
    });
  });
  afterEach(async () => {
    await act(async () => root.unmount());
  });

  it('guides normal and quiet speech for six seconds each in one enrollment', async () => {
    const pending = deferred<VoiceProfile>();
    jest.mocked(c.enrollVoiceprint).mockReturnValue(pending.promise);
    const hasText = (text: string) =>
      root.root.findAllByType(Text).some(node => node.props.children === text);
    await act(async () => {
      button().props.onPress();
    });
    expect(hasText('voiceprintNormalStage · 6 seconds')).toBe(true);
    await act(async () => {
      progress(0.25);
    });
    expect(hasText('voiceprintNormalStage · 3 seconds')).toBe(true);
    await act(async () => {
      progress(0.5);
    });
    expect(hasText('voiceprintQuietStage · 6 seconds')).toBe(true);
    await act(async () => {
      progress(0.75);
    });
    expect(hasText('voiceprintQuietStage · 3 seconds')).toBe(true);
    await act(async () => {
      pending.resolve(profile);
    });
    expect(c.enrollVoiceprint).toHaveBeenCalledTimes(1);
  });

  it('ignores old progress and disables reenrollment until cancellation settles', async () => {
    const pending = deferred<VoiceProfile>();
    jest.mocked(c.enrollVoiceprint).mockReturnValue(pending.promise);
    jest.mocked(c.cancelVoiceprint).mockImplementation(async () => {
      await pending.promise.catch(() => {});
    });
    await act(async () => {
      button().props.onPress();
    });
    await act(async () => {
      progress(0.4);
      button().props.onPress();
    });
    expect(button().props.label).toBe('voiceprintCancelling');
    expect(button().props.disabled).toBe(true);
    await act(async () => {
      progress(1);
      button().props.onPress();
    });
    expect(button().props.label).toBe('voiceprintCancelling');
    expect(c.enrollVoiceprint).toHaveBeenCalledTimes(1);
    await act(async () => {
      pending.reject(new Error('CANCELLED'));
    });
    expect(button().props.label).toBe('voiceprintEnroll');
    expect(c.saveVoiceprint).not.toHaveBeenCalled();
  });

  it('blocks cancel and both back paths while saving, then restores them after persistence', async () => {
    const saving = deferred<void>();
    jest.mocked(c.enrollVoiceprint).mockResolvedValue(profile);
    jest.mocked(c.saveVoiceprint).mockReturnValue(saving.promise);
    await act(async () => {
      button().props.onPress();
    });
    expect(button().props.label).toBe('voiceprintSaving');
    expect(button().props.disabled).toBe(true);
    await act(async () => {
      button().props.onPress();
      back().props.onPress();
      root.root.findByType(Modal).props.onRequestClose();
    });
    expect(close).not.toHaveBeenCalled();
    expect(c.cancelVoiceprint).not.toHaveBeenCalled();
    expect(c.saveVoiceprint).toHaveBeenCalledTimes(1);
    await act(async () => {
      saving.resolve();
    });
    expect(hasSavedMessage()).toBe(true);
    await act(async () => {
      back().props.onPress();
    });
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('allows leaving a cancelled permission wait and discards its eventual result', async () => {
    const pending = deferred<VoiceProfile>();
    jest.mocked(c.enrollVoiceprint).mockReturnValue(pending.promise);
    await act(async () => {
      button().props.onPress();
    });
    await act(async () => {
      back().props.onPress();
    });
    expect(close).toHaveBeenCalledTimes(1);
    await act(async () => {
      pending.resolve(profile);
    });
    expect(c.saveVoiceprint).not.toHaveBeenCalled();
    expect(hasSavedMessage()).toBe(false);
  });

  it('does not show completion from a page unmounted while storage was pending', async () => {
    const saving = deferred<void>();
    jest.mocked(c.enrollVoiceprint).mockResolvedValue(profile);
    jest.mocked(c.saveVoiceprint).mockReturnValue(saving.promise);
    await act(async () => {
      button().props.onPress();
    });
    await act(async () => {
      root.unmount();
    });
    await act(async () => {
      root = create(<Harness />);
    });
    await act(async () => {
      saving.resolve();
    });
    expect(hasSavedMessage()).toBe(false);
    expect(close).not.toHaveBeenCalled();
  });
});
