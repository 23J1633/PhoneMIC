import React from 'react';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';
import { AppState, PermissionsAndroid } from 'react-native';
import { Native, events } from '../src/native';
import { usePhoneMic, type PhoneMicController } from '../src/usePhoneMic';
import {
  defaultSettings,
  VOICEPRINT_MODEL,
  type Packet,
  type VoiceProfile,
} from '../src/types';

jest.mock('../src/native', () => {
  const listeners = new Map<string, Set<(event: unknown) => void>>();
  return {
    Native: Object.fromEntries(
      [
        'prepareSpeech',
        'startSpeech',
        'stopSpeech',
        'cancelSpeech',
        'cancelVoiceprint',
        'startVoiceprint',
        'setHeadsetControls',
        'connect',
        'discover',
        'disconnect',
        'disconnectBluetooth',
        'send',
        'sendBluetooth',
        'keepAwake',
        'haptic',
        'getStore',
        'setStore',
      ].map(name => [name, jest.fn().mockResolvedValue(true)]),
    ),
    events: {
      addListener: (name: string, callback: (event: unknown) => void) => {
        if (!listeners.has(name)) listeners.set(name, new Set());
        listeners.get(name)!.add(callback);
        return { remove: () => listeners.get(name)?.delete(callback) };
      },
      emit: (name: string, event: unknown) =>
        listeners.get(name)?.forEach(callback => callback(event)),
    },
    store: {
      get: async (key: string, fallback: unknown) => {
        const raw = await require('../src/native').Native.getStore(key);
        return raw == null ? fallback : JSON.parse(raw);
      },
      set: async (key: string, value: unknown) =>
        require('../src/native').Native.setStore(key, JSON.stringify(value)),
    },
  };
});

describe('recording actions and reconnection', () => {
  let c: PhoneMicController;
  let root: ReactTestRenderer;
  let renderCount = 0;
  const emit = (name: string, event: unknown) =>
    (
      events as unknown as { emit: (name: string, event: unknown) => void }
    ).emit(name, event);
  const packets = () =>
    jest
      .mocked(Native.send)
      .mock.calls.map(([data]) => JSON.parse(data) as Packet)
      .filter(packet => packet.session);
  const transport = (state: string) =>
    emit('PhoneMicTransport', { type: 'status', transport: 'lan', state });
  const acknowledge = (packet: Packet) =>
    emit('PhoneMicTransport', {
      type: 'message',
      transport: 'lan',
      data: JSON.stringify({
        type: 'ack',
        session: packet.session,
        seq: packet.seq,
      }),
    });
  function Harness() {
    renderCount++;
    c = usePhoneMic();
    return null;
  }
  beforeEach(async () => {
    jest.useFakeTimers();
    jest.clearAllMocks();
    jest.mocked(Native.discover).mockResolvedValue([]);
    renderCount = 0;
    jest.spyOn(PermissionsAndroid, 'check').mockResolvedValue(true);
    jest
      .spyOn(AppState, 'addEventListener')
      .mockReturnValue({ remove: jest.fn() });
    jest.mocked(Native.getStore).mockImplementation(async key =>
      key === 'settings'
        ? JSON.stringify({
            ...defaultSettings,
            afterInput: 'send',
            guideShortcut: { key: 'PageDown', modifiers: ['Control'] },
          })
        : key === 'computer'
        ? JSON.stringify({
            id: 'computer',
            host: '192.168.1.2',
            fingerprint: 'a'.repeat(64),
            token: 'token',
            name: 'Computer',
          })
        : null,
    );
    await act(async () => {
      root = create(<Harness />);
    });
    await act(async () => {
      transport('connected');
    });
  });
  afterEach(async () => {
    await act(async () => root.unmount());
    jest.useRealTimers();
    jest.restoreAllMocks();
  });
  const start = async () => {
    await act(async () => {
      c.press();
    });
    await act(async () => {
      acknowledge(packets().at(-1)!);
    });
    return jest.mocked(Native.startSpeech).mock.calls.at(-1)![1];
  };
  const finish = async (id: string) => {
    await act(async () => {
      emit('PhoneMicVoice', {
        type: 'transcript',
        session: id,
        text: '识别后的文字',
        final: true,
      });
    });
  };
  const profile = (): VoiceProfile => ({
    model: VOICEPRINT_MODEL,
    embedding: [1, 0],
    templates: [
      [1, 0],
      [0.9, 0.1],
    ],
    createdAt: 1,
    durationMs: 10000,
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

  it('clears input without opening the microphone and waits for the clear acknowledgement', async () => {
    await act(async () => {
      expect(c.prepareInputClear()).toBe(true);
    });
    expect(Native.startSpeech).not.toHaveBeenCalled();
    await act(async () => {
      acknowledge(packets().at(-1)!);
    });
    await act(async () => {
      c.clearInput();
    });
    expect(packets().at(-1)?.type).toBe('clear');
    expect(c.clearingInput).toBe(true);
    expect(c.notice).not.toBe('inputCleared');
    await act(async () => {
      acknowledge(packets().at(-1)!);
    });
    expect(c.clearingInput).toBe(false);
    expect(c.notice).toBe('inputCleared');
    expect(c.history).toHaveLength(0);
  });
  it('cancels a prepared clear without starting a recording or honoring late acknowledgements', async () => {
    await act(async () => {
      c.prepareInputClear();
      c.press();
    });
    expect(Native.startSpeech).not.toHaveBeenCalled();
    const begin = packets().at(-1)!;
    await act(async () => {
      c.cancelInputClear();
      acknowledge(begin);
      c.clearInput();
    });
    expect(packets().filter(packet => packet.type === 'clear')).toHaveLength(0);
    expect(c.clearingInput).toBe(false);
  });
  it('does not clear after a network handover even if the old focus acknowledgement arrives', async () => {
    await act(async () => {
      c.prepareInputClear();
      c.clearInput();
    });
    const begin = packets().at(-1)!;
    await act(async () => {
      transport('disconnected');
      transport('connected');
      acknowledge(begin);
    });
    expect(packets().filter(packet => packet.type === 'clear')).toHaveLength(0);
    expect(c.clearingInput).toBe(false);
    expect(c.notice).toBe('clearInputFailed');
  });
  it('never repeats a clear with a lost acknowledgement on reconnection', async () => {
    await act(async () => {
      c.prepareInputClear();
    });
    await act(async () => {
      acknowledge(packets().at(-1)!);
      c.clearInput();
    });
    await act(async () => {
      jest.advanceTimersByTime(6000);
      transport('disconnected');
      transport('connected');
    });
    expect(packets().filter(packet => packet.type === 'clear')).toHaveLength(1);
    expect(c.notice).toBe('clearInputFailed');
    expect(c.clearingInput).toBe(false);
  });
  it('reports an unsupported desktop command without blaming the input focus', async () => {
    await act(async () => {
      c.prepareInputClear();
    });
    await act(async () => {
      acknowledge(packets().at(-1)!);
      c.clearInput();
    });
    const packet = packets().at(-1)!;
    await act(async () => {
      emit('PhoneMicTransport', {
        type: 'message',
        transport: 'lan',
        data: JSON.stringify({
          type: 'error',
          session: packet.session,
          seq: packet.seq,
          code: 'PROTOCOL',
          message: 'Unknown message',
        }),
      });
    });
    expect(c.notice).toBe('clearInputUnsupported');
    expect(c.clearingInput).toBe(false);
  });
  it('refuses an input clear during dictation or when disconnected', async () => {
    await start();
    await act(async () => {
      expect(c.prepareInputClear()).toBe(false);
      c.discardRecording();
    });
    await act(async () => {
      transport('disconnected');
    });
    await act(async () => {
      expect(c.prepareInputClear()).toBe(false);
    });
    expect(c.notice).toBe('clearInputDisconnected');
    expect(packets().filter(packet => packet.type === 'clear')).toHaveLength(0);
  });
  it('cancels a pending permission request without starting enrollment or trapping the page', async () => {
    const permission = deferred<boolean>();
    jest
      .mocked(PermissionsAndroid.check)
      .mockImplementationOnce(() => permission.promise);
    let pending!: Promise<VoiceProfile | string>;
    await act(async () => {
      pending = c.enrollVoiceprint().catch(error => error.message);
    });
    await act(async () => {
      await c.cancelVoiceprint();
    });
    expect(Native.startVoiceprint).not.toHaveBeenCalled();
    await act(async () => {
      permission.resolve(true);
    });
    await expect(pending).resolves.toBe('CANCELLED');
    expect(Native.startVoiceprint).not.toHaveBeenCalled();
  });
  it('waits for native cancellation before accepting another enrollment', async () => {
    const native = deferred<VoiceProfile>();
    jest
      .mocked(Native.startVoiceprint)
      .mockImplementationOnce(() => native.promise);
    let pending!: Promise<VoiceProfile | string>;
    let cancellation!: Promise<void>;
    let cancelled = false;
    await act(async () => {
      pending = c.enrollVoiceprint().catch(error => error.message);
    });
    await act(async () => {
      cancellation = c.cancelVoiceprint().then(() => {
        cancelled = true;
      });
    });
    expect(cancelled).toBe(false);
    await expect(c.enrollVoiceprint()).rejects.toThrow('BUSY');
    await act(async () => {
      native.resolve(profile());
      await cancellation;
    });
    await expect(pending).resolves.toBe('CANCELLED');
    expect(cancelled).toBe(true);
    jest.mocked(Native.startVoiceprint).mockResolvedValueOnce(profile());
    await expect(c.enrollVoiceprint()).resolves.toMatchObject({
      model: VOICEPRINT_MODEL,
    });
  });
  it('preserves incompatible stored voiceprints while disabling their filter', async () => {
    await act(async () => root.unmount());
    const incompatible = { ...profile(), model: 'different-speaker-model' };
    jest
      .mocked(Native.getStore)
      .mockImplementation(async key =>
        key === 'settings'
          ? JSON.stringify({ ...defaultSettings, voiceFilter: true })
          : key === 'voiceProfile'
          ? JSON.stringify(incompatible)
          : null,
      );
    jest.mocked(Native.setStore).mockClear();
    await act(async () => {
      root = create(<Harness />);
    });
    expect(c.voiceProfile).toBeNull();
    expect(c.voiceProfileIncompatible).toBe(true);
    expect(c.settings.voiceFilter).toBe(false);
    expect(c.notice).toBe('voiceprintIncompatible');
    expect(
      jest
        .mocked(Native.setStore)
        .mock.calls.some(([key]) => key === 'voiceProfile'),
    ).toBe(false);
    await act(async () => {
      c.press();
    });
    expect(
      jest.mocked(Native.startSpeech).mock.calls.at(-1)![2].speakerEmbedding,
    ).toBeUndefined();
  });
  it('rejects malformed replacements without overwriting a saved voiceprint', async () => {
    await act(async () => {
      await c.saveVoiceprint(profile());
    });
    jest.mocked(Native.setStore).mockClear();
    await act(async () => {
      await expect(
        c.saveVoiceprint({ ...profile(), templates: [[1]] }),
      ).rejects.toThrow('VOICEPRINT_INCOMPATIBLE');
    });
    expect(c.voiceProfile).toEqual(profile());
    expect(Native.setStore).not.toHaveBeenCalled();
  });
  it('checks the voiceprint again at use time and never sends invalid vectors to native', async () => {
    await act(async () => {
      await c.saveVoiceprint(profile());
    });
    c.voiceProfile!.embedding[0] = Number.NaN;
    await start();
    expect(c.settings.voiceFilter).toBe(false);
    expect(c.voiceProfileIncompatible).toBe(true);
    expect(c.notice).toBe('voiceprintIncompatible');
    expect(
      jest.mocked(Native.startSpeech).mock.calls.at(-1)![2].speakerEmbedding,
    ).toBeUndefined();
  });
  it('keeps saving locked until both the profile and filter setting reach storage', async () => {
    const settingsSaved = deferred<boolean>();
    jest
      .mocked(Native.setStore)
      .mockResolvedValueOnce(true)
      .mockImplementationOnce(() => settingsSaved.promise);
    let saved!: Promise<void>;
    await act(async () => {
      saved = c.saveVoiceprint(profile());
    });
    expect(c.settings.voiceFilter).toBe(false);
    await act(async () => {
      c.press();
    });
    expect(Native.startSpeech).not.toHaveBeenCalled();
    await act(async () => {
      settingsSaved.resolve(true);
      await saved;
    });
    expect(c.voiceProfile).toEqual(profile());
    expect(c.settings.voiceFilter).toBe(true);
  });
  it('keeps the saved profile in memory if persisting the filter setting fails', async () => {
    jest
      .mocked(Native.setStore)
      .mockResolvedValueOnce(true)
      .mockRejectedValueOnce(new Error('Storage unavailable'));
    await act(async () => {
      await expect(c.saveVoiceprint(profile())).rejects.toThrow(
        'Storage unavailable',
      );
    });
    expect(c.voiceProfile).toEqual(profile());
    expect(c.settings.voiceFilter).toBe(false);
    expect(c.voiceProfileIncompatible).toBe(false);
  });

  it('ordinary tapping types without running the saved automatic send shortcut', async () => {
    const id = await start();
    await act(async () => {
      c.press();
    });
    await finish(id);
    const final = packets().find(packet => packet.type === 'text')!;
    expect(final).toMatchObject({ text: '识别后的文字', final: true });
    expect(final.shortcut).toBeUndefined();
    expect(jest.mocked(Native.startSpeech).mock.calls[0][2].streaming).toBe(
      false,
    );
    expect(jest.mocked(Native.startSpeech).mock.calls[0][2].preview).toBe(true);
  });
  it('keeps microphone level events off the application render path', async () => {
    const id = await start();
    const before = renderCount;
    for (let index = 0; index < 20; index++) {
      await act(async () => {
        emit('PhoneMicVoice', {
          type: 'level',
          session: id,
          value: (index + 1) / 20,
        });
      });
    }
    expect(renderCount - before).toBe(0);
    expect(c.levelRef.current).toBe(1);
  });
  it('stops the waveform immediately and ignores late microphone events while finishing text', async () => {
    const id = await start();
    await act(async () => {
      emit('PhoneMicVoice', { type: 'level', session: id, value: 0.9 });
    });
    expect(c.levelRef.current).toBe(0.9);
    await act(async () => {
      c.press();
    });
    expect(c.recording).toBe(false);
    expect(c.openingMic).toBe(false);
    expect(c.levelRef.current).toBe(0);
    expect(c.busy).toBe(true);
    await act(async () => {
      emit('PhoneMicVoice', { type: 'state', state: 'recording', session: id });
      emit('PhoneMicVoice', { type: 'level', session: id, value: 1 });
    });
    expect(c.recording).toBe(false);
    expect(c.levelRef.current).toBe(0);
    await finish(id);
    expect(packets().at(-1)).toMatchObject({
      text: '识别后的文字',
      final: true,
    });
  });
  it('keeps native processing quiet even if a queued level arrives afterwards', async () => {
    const id = await start();
    await act(async () => {
      emit('PhoneMicVoice', { type: 'level', session: id, value: 0.8 });
      emit('PhoneMicVoice', {
        type: 'state',
        state: 'processing',
        session: id,
      });
      emit('PhoneMicVoice', { type: 'level', session: id, value: 1 });
    });
    expect(c.recording).toBe(false);
    expect(c.levelRef.current).toBe(0);
    await finish(id);
    expect(packets().at(-1)?.text).toBe('识别后的文字');
  });
  it('does not redraw the app for an invisible recording timer', async () => {
    await start();
    const before = renderCount;
    for (let index = 0; index < 10; index++) {
      await act(async () => {
        jest.advanceTimersByTime(200);
      });
    }
    expect(renderCount - before).toBe(0);
  });
  it('keeps the recording limit active for a background headset session', async () => {
    await act(async () => {
      emit('PhoneMicHeadset', { action: 'down' });
    });
    await act(async () => {
      acknowledge(packets().at(-1)!);
      jest
        .mocked(AppState.addEventListener)
        .mock.calls.forEach(([type, listener]) => {
          if (type === 'change') listener('background');
        });
    });
    expect(c.recording).toBe(true);
    await act(async () => {
      jest.advanceTimersByTime(599999);
    });
    expect(Native.stopSpeech).not.toHaveBeenCalled();
    await act(async () => {
      jest.advanceTimersByTime(1);
    });
    expect(Native.stopSpeech).toHaveBeenCalledTimes(1);
    expect(c.notice).toBe('recordingLimit');
    await act(async () => {
      jest.advanceTimersByTime(2000);
    });
    expect(Native.stopSpeech).toHaveBeenCalledTimes(1);
  });
  it('does not let the discarded session limit stop a fresh recording', async () => {
    await start();
    await act(async () => {
      jest.advanceTimersByTime(300000);
    });
    await act(async () => {
      c.discardRecording();
    });
    const id = await start();
    await act(async () => {
      jest.advanceTimersByTime(300000);
    });
    expect(Native.stopSpeech).not.toHaveBeenCalled();
    expect(c.recording).toBe(true);
    await act(async () => {
      c.press();
    });
    await finish(id);
    await act(async () => {
      acknowledge(packets().at(-1)!);
    });
    await act(async () => {
      jest.advanceTimersByTime(300000);
    });
    expect(Native.stopSpeech).toHaveBeenCalledTimes(1);
  });
  it('shows live draft text locally and sends the corrected final text', async () => {
    const id = await start();
    await act(async () => {
      emit('PhoneMicVoice', {
        type: 'transcript',
        session: id,
        text: '实时草稿',
        final: false,
      });
    });
    expect(c.preview).toBe('实时草稿');
    expect(packets().filter(packet => packet.type === 'text')).toHaveLength(0);
    await act(async () => {
      emit('PhoneMicVoice', {
        type: 'transcript',
        session: id,
        text: '最终修正文字',
        final: true,
      });
    });
    expect(packets().at(-1)?.text).toBe('最终修正文字');
  });
  it('reports empty recognition instead of silently doing nothing', async () => {
    const id = await start();
    await act(async () => {
      emit('PhoneMicVoice', {
        type: 'transcript',
        session: id,
        text: '',
        final: true,
      });
    });
    expect(c.notice).toBe('recognitionEmpty');
    await act(async () => {
      acknowledge(packets().at(-1)!);
    });
    expect(c.notice).toBe('recognitionEmpty');
  });
  it('uses the explicitly chosen guide shortcut only after recognition finishes', async () => {
    const id = await start();
    await act(async () => {
      c.finishWithAction('guide');
    });
    expect(packets().filter(packet => packet.shortcut)).toHaveLength(0);
    await finish(id);
    expect(packets().at(-1)?.shortcut).toEqual({
      key: 'PageDown',
      modifiers: ['Control'],
    });
  });
  it('keeps an unsent dictation through a temporary disconnect and delivers it once', async () => {
    const id = await start();
    await act(async () => {
      transport('disconnected');
    });
    expect(c.recording).toBe(true);
    expect(Native.cancelSpeech).not.toHaveBeenCalled();
    await finish(id);
    expect(packets().filter(packet => packet.type === 'text')).toHaveLength(0);
    await act(async () => {
      transport('connected');
    });
    await act(async () => {
      acknowledge(packets().at(-1)!);
    });
    expect(packets().filter(packet => packet.type === 'text')).toHaveLength(1);
    await act(async () => {
      acknowledge(packets().at(-1)!);
    });
    expect(c.busy).toBe(false);
  });
  it('reconnects to the saved address when network discovery is unavailable', async () => {
    jest
      .mocked(Native.discover)
      .mockRejectedValue(new Error('UDP unavailable'));
    jest.mocked(Native.connect).mockClear();
    await act(async () => {
      transport('disconnected');
      jest.advanceTimersByTime(400);
    });
    expect(Native.discover).toHaveBeenCalled();
    expect(Native.connect).toHaveBeenCalledWith(
      expect.stringContaining('192.168.1.2'),
    );
  });
  it('does not replay text or a shortcut whose acknowledgement was lost', async () => {
    const id = await start();
    await act(async () => {
      c.finishWithAction('send');
    });
    await finish(id);
    await act(async () => {
      transport('disconnected');
    });
    await act(async () => {
      transport('connected');
    });
    expect(packets().filter(packet => packet.type === 'text')).toHaveLength(1);
    expect(packets().filter(packet => packet.shortcut)).toHaveLength(1);
    expect(c.history[0].status).toBe('uncertain');
  });
  it('toggles dictation on headset clicks in tap mode', async () => {
    await act(async () => {
      emit('PhoneMicHeadset', { action: 'down' });
    });
    expect(Native.startSpeech).toHaveBeenCalledTimes(1);
    await act(async () => {
      emit('PhoneMicHeadset', { action: 'up' });
    });
    expect(Native.startSpeech).toHaveBeenCalledTimes(1);
    expect(Native.stopSpeech).not.toHaveBeenCalled();
    expect(jest.mocked(Native.startSpeech).mock.calls[0][2]).toMatchObject({
      streaming: false,
      background: true,
    });
    await act(async () => {
      emit('PhoneMicHeadset', { action: 'down' });
      emit('PhoneMicHeadset', { action: 'up' });
    });
    expect(Native.stopSpeech).toHaveBeenCalledTimes(1);
  });
  it('clears the draft without typing, saving history, or accepting a late transcript', async () => {
    const id = await start();
    await act(async () => {
      emit('PhoneMicVoice', {
        type: 'transcript',
        session: id,
        text: 'Discard this draft',
        final: false,
      });
    });
    expect(c.preview).toBe('Discard this draft');
    await act(async () => {
      c.discardRecording();
    });
    expect(Native.cancelSpeech).toHaveBeenCalledTimes(1);
    expect(c.recording).toBe(false);
    expect(c.busy).toBe(false);
    expect(c.preview).toBe('');
    expect(c.history).toHaveLength(0);
    expect(packets().filter(packet => packet.type === 'cancel')).toHaveLength(
      1,
    );
    await finish(id);
    expect(
      packets().filter(packet => packet.type === 'text' || packet.shortcut),
    ).toHaveLength(0);
    expect(c.preview).toBe('');
    expect(c.history).toHaveLength(0);
    expect(c.notice).toBe('recordingDiscarded');
  });
  it('does not let a cancelled microphone startup end the next recording', async () => {
    let rejectOld!: (error: unknown) => void;
    let resolveNew!: (value: boolean) => void;
    jest
      .mocked(Native.startSpeech)
      .mockImplementationOnce(
        () =>
          new Promise((_, reject) => {
            rejectOld = reject;
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise(resolve => {
            resolveNew = resolve;
          }),
      );
    const oldId = await start();
    await act(async () => {
      c.discardRecording();
    });
    await act(async () => {
      c.press();
    });
    const newId = jest.mocked(Native.startSpeech).mock.calls.at(-1)![1];
    expect(newId).not.toBe(oldId);
    expect(c.openingMic).toBe(true);
    await act(async () => {
      rejectOld({ code: 'CANCELLED' });
    });
    expect(c.recording).toBe(true);
    expect(c.openingMic).toBe(true);
    await finish(oldId);
    expect(c.recording).toBe(true);
    await act(async () => {
      resolveNew(true);
    });
    expect(c.openingMic).toBe(false);
  });
  it('treats repeated play and pause commands as start and stop rather than extra toggles', async () => {
    await act(async () => {
      emit('PhoneMicHeadset', { action: 'start' });
      emit('PhoneMicHeadset', { action: 'start' });
    });
    expect(Native.startSpeech).toHaveBeenCalledTimes(1);
    expect(Native.stopSpeech).not.toHaveBeenCalled();
    await act(async () => {
      emit('PhoneMicHeadset', { action: 'stop' });
      emit('PhoneMicHeadset', { action: 'stop' });
    });
    expect(Native.stopSpeech).toHaveBeenCalledTimes(1);
    expect(Native.startSpeech).toHaveBeenCalledTimes(1);
  });
  it('starts on headset press and finishes on release in hold mode', async () => {
    await act(async () => {
      c.updateSettings({ mode: 'hold' });
    });
    await act(async () => {
      emit('PhoneMicHeadset', { action: 'down' });
    });
    expect(Native.startSpeech).toHaveBeenCalledTimes(1);
    expect(jest.mocked(Native.startSpeech).mock.calls[0][2]).toMatchObject({
      streaming: true,
      background: true,
    });
    expect(Native.stopSpeech).not.toHaveBeenCalled();
    await act(async () => {
      emit('PhoneMicHeadset', { action: 'up' });
    });
    expect(Native.stopSpeech).toHaveBeenCalledTimes(1);
  });
});
