import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  AppState,
  Linking,
  PermissionsAndroid,
  Platform,
} from 'react-native';
import { events, Native, store } from './native';
import { DictationSession } from './session';
import { completionShortcut } from './shortcuts';
import { defaultSettings, isCompatibleVoiceProfile } from './types';
import type {
  BluetoothComputer,
  Computer,
  CompletionAction,
  HistoryEntry,
  Reply,
  Settings,
  TransportEvent,
  VoiceEvent,
  VoiceProfile,
} from './types';
import { translator, type TextKey } from './i18n';

interface ActiveDictation {
  id: string;
  started: number;
  settings: Settings;
  text: string;
  ended: boolean;
  stopping: boolean;
  failed: boolean;
  acknowledged: boolean;
  attempted: boolean;
  duration?: number;
  session?: DictationSession;
  origin?: 'headset';
  filtered?: boolean;
}
interface EnrollmentTask {
  cancelled: boolean;
  nativeStarted: boolean;
  completion?: Promise<VoiceProfile>;
}
export function usePhoneMic() {
  const [settings, setSettings] = useState(defaultSettings);
  const [computer, setComputer] = useState<Computer | null>(null);
  const [bluetoothComputer, setBluetoothComputer] =
    useState<BluetoothComputer | null>(null);
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const [voiceProfile, setVoiceProfile] = useState<VoiceProfile | null>(null);
  const [voiceProfileIncompatible, setVoiceProfileIncompatible] =
    useState(false);
  const [hydrated, setHydrated] = useState(false);
  const [modelReady, setModelReady] = useState(false);
  const [modelError, setModelError] = useState(false);
  const [connection, setConnection] = useState<
    'connected' | 'connecting' | 'disconnected' | 'unauthorized'
  >('disconnected');
  const [preview, setPreview] = useState('');
  const [previewVisible, setPreviewVisible] = useState(false);
  const [recording, setRecording] = useState(false);
  const [openingMic, setOpeningMic] = useState(false);
  const [busy, setBusy] = useState(false);
  const levelRef = useRef(0);
  const [latency, setLatency] = useState<number | null>(null);
  const [notice, setNotice] = useState<TextKey | null>(null);
  const hasTarget =
    settings.transport === 'lan' ? !!computer : !!bluetoothComputer;
  const state = useRef({
    settings,
    connection,
    modelReady,
    hydrated,
    hasTarget,
  });
  state.current = { settings, connection, modelReady, hydrated, hasTarget };
  const historyRef = useRef<HistoryEntry[]>([]);
  const active = useRef<ActiveDictation | null>(null);
  const held = useRef(false);
  const headsetHeld = useRef(false);
  const starting = useRef(false);
  const startEpoch = useRef(0);
  const enrollment = useRef<EnrollmentTask | null>(null);
  const savingVoiceProfile = useRef(false);
  const recordingLimitTimer = useRef<ReturnType<typeof setTimeout> | undefined>(
    undefined,
  );
  const deliveryTimer = useRef<ReturnType<typeof setTimeout> | undefined>(
    undefined,
  );
  const previewTimer = useRef<ReturnType<typeof setTimeout> | undefined>(
    undefined,
  );
  const deliver = useRef<(current: ActiveDictation) => void>(() => {});
  const noticeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(
    undefined,
  );
  const t = translator(settings.uiLanguage);
  const showNotice = useCallback((key: TextKey) => {
    clearTimeout(noticeTimer.current);
    setNotice(key);
    noticeTimer.current = setTimeout(() => setNotice(null), 6500);
  }, []);
  const saveHistory = useCallback(
    (transform: (old: HistoryEntry[]) => HistoryEntry[]) => {
      const next = transform(historyRef.current).slice(0, 200);
      historyRef.current = next;
      setHistory(next);
      void store.set('history', next).catch(() => showNotice('storageFailed'));
    },
    [showNotice],
  );

  useEffect(() => {
    let mounted = true;
    void Promise.all([
      store.get<Settings>('settings', defaultSettings),
      store.get<Computer | null>('computer', null),
      store.get<BluetoothComputer | null>('bluetoothComputer', null),
      store.get<HistoryEntry[]>('history', []),
      store.get<VoiceProfile | null>('voiceProfile', null),
    ])
      .then(
        ([
          savedSettings,
          savedComputer,
          savedBluetooth,
          savedHistory,
          savedVoice,
        ]) => {
          if (!mounted) {
            return;
          }
          const compatibleVoice = isCompatibleVoiceProfile(savedVoice);
          const restoredSettings = {
            ...defaultSettings,
            ...savedSettings,
            language: 'auto' as const,
            haptics: true,
            voiceFilter: !!savedSettings.voiceFilter && compatibleVoice,
          };
          setSettings(restoredSettings);
          setVoiceProfile(compatibleVoice ? savedVoice : null);
          setVoiceProfileIncompatible(savedVoice !== null && !compatibleVoice);
          if (savedVoice !== null && !compatibleVoice) {
            showNotice('voiceprintIncompatible');
            if (savedSettings.voiceFilter)
              void store
                .set('settings', restoredSettings)
                .catch(() => showNotice('storageFailed'));
          }
          setComputer(savedComputer);
          setBluetoothComputer(savedBluetooth);
          historyRef.current = savedHistory;
          setHistory(savedHistory);
          setHydrated(true);
        },
      )
      .catch(() => {
        if (mounted) {
          setHydrated(true);
          showNotice('storageFailed');
        }
      });
    return () => {
      mounted = false;
      clearTimeout(noticeTimer.current);
      clearTimeout(deliveryTimer.current);
      clearTimeout(previewTimer.current);
      clearTimeout(recordingLimitTimer.current);
      active.current?.session?.dispose();
      if (enrollment.current) enrollment.current.cancelled = true;
      enrollment.current = null;
      Native.cancelSpeech();
      Native.cancelVoiceprint();
      Native.keepAwake(false);
    };
  }, [showNotice]);

  useEffect(() => {
    if (!hydrated) return;
    Native.setHeadsetControls(settings.headsetControls && modelReady);
    return () => Native.setHeadsetControls(false);
  }, [hydrated, modelReady, settings.headsetControls]);

  useEffect(() => {
    if (!hydrated) {
      return;
    }
    let disposed = false;
    setModelReady(false);
    setModelError(false);
    void Native.prepareSpeech(settings.language)
      .then(() => {
        if (!disposed) {
          setModelReady(true);
        }
      })
      .catch(() => {
        if (!disposed) {
          setModelError(true);
          showNotice('modelFailed');
        }
      });
    return () => {
      disposed = true;
    };
  }, [hydrated, settings.language, showNotice]);

  const markHistory = useCallback(
    (current: ActiveDictation) => {
      if (!current.text.trim()) {
        return;
      }
      const entry: HistoryEntry = {
        id: current.id,
        text: current.text.trim(),
        language: current.settings.language,
        mode: current.settings.mode,
        createdAt: current.started,
        duration: current.duration ?? Date.now() - current.started,
        status: current.acknowledged
          ? 'sent'
          : current.attempted
          ? 'uncertain'
          : 'saved',
        transport: current.settings.transport,
      };
      saveHistory(old => [
        entry,
        ...old.filter(item => item.id !== current.id),
      ]);
    },
    [saveHistory],
  );
  const finish = useCallback(
    (text: string) => {
      const current = active.current;
      if (!current || current.ended) {
        return;
      }
      current.text = text;
      current.ended = true;
      clearTimeout(recordingLimitTimer.current);
      current.duration = Date.now() - current.started;
      setPreview(text);
      setPreviewVisible(!!text.trim());
      clearTimeout(previewTimer.current);
      previewTimer.current = setTimeout(() => setPreviewVisible(false), 4000);
      setRecording(false);
      setOpeningMic(false);
      levelRef.current = 0;
      Native.keepAwake(false);
      if (current.settings.haptics && !current.stopping) {
        Native.haptic();
      }
      markHistory(current);
      if (!text.trim() && !current.failed && !current.filtered)
        showNotice('recognitionEmpty');
      if (!current.failed) {
        deliver.current(current);
      } else {
        setBusy(false);
        active.current = null;
      }
    },
    [markHistory, showNotice],
  );
  const failCurrent = useCallback(
    (message: TextKey) => {
      const current = active.current;
      if (!current || current.failed) {
        return;
      }
      current.failed = true;
      clearTimeout(deliveryTimer.current);
      deliveryTimer.current = undefined;
      current.session?.abort('INPUT_CANCELLED');
      showNotice(message);
      if (current.ended) {
        markHistory(current);
        setBusy(false);
        active.current = null;
      }
    },
    [markHistory, showNotice],
  );
  const createSession = useCallback(
    (current: ActiveDictation) => {
      return new DictationSession(
        `${current.id}-${Math.random().toString(36).slice(2, 8)}`,
        current.settings.mode === 'hold',
        packet => {
          if (packet.type === 'text') current.attempted = true;
          const payload = JSON.stringify(packet);
          return current.settings.transport === 'lan'
            ? Native.send(payload)
            : Native.sendBluetooth(payload);
        },
        () => {
          clearTimeout(deliveryTimer.current);
          deliveryTimer.current = undefined;
          current.acknowledged = true;
          markHistory(current);
          if (active.current === current) {
            setBusy(false);
            active.current = null;
          }
          if (current.text.trim()) showNotice('inputComplete');
        },
        message => {
          console.warn('[PhoneMIC] Input transport:', message);
          if (active.current === current) {
            failCurrent('inputFailed');
          }
        },
        current.settings.transport === 'bluetooth'
          ? packet => Math.max(6000, (packet.text?.length || 0) * 15 + 5000)
          : 6000,
        completionShortcut(current.settings),
      );
    },
    [failCurrent, markHistory, showNotice],
  );
  deliver.current = current => {
    if (active.current !== current || current.failed) return;
    if (state.current.connection === 'connected') {
      clearTimeout(deliveryTimer.current);
      deliveryTimer.current = undefined;
      if (!current.session) {
        if (current.ended && !current.text.trim()) {
          setBusy(false);
          active.current = null;
          return;
        }
        current.session = createSession(current);
        current.session.begin();
      }
      current.session.update(current.text, current.ended);
    } else if (current.ended) {
      if (!current.text.trim() || !state.current.hasTarget) {
        if (current.text.trim() && !state.current.hasTarget)
          showNotice('savedLocally');
        setBusy(false);
        active.current = null;
        return;
      }
      if (!deliveryTimer.current) {
        deliveryTimer.current = setTimeout(() => {
          deliveryTimer.current = undefined;
          if (active.current === current) failCurrent('connectionLost');
        }, 8000);
      }
    }
  };

  useEffect(() => {
    const subscription = events.addListener(
      'PhoneMicVoice',
      (event: VoiceEvent) => {
        if (event.session && event.session !== active.current?.id) return;
        if (event.type === 'level') {
          if (
            !active.current ||
            active.current.ended ||
            active.current.stopping
          )
            return;
          levelRef.current = event.value || 0;
          return;
        }
        if (event.type === 'filtered') {
          if (active.current) active.current.filtered = true;
          showNotice('voiceNotMatched');
          return;
        }
        if (event.type === 'inputSource') {
          if (event.fallback) showNotice('headsetMicFallback');
          return;
        }
        if (event.type === 'transcript') {
          const current = active.current;
          if (!current || current.ended) {
            return;
          }
          current.text = event.text || '';
          setPreview(current.text);
          if (event.final) {
            finish(current.text);
          } else if (!current.failed) {
            current.session?.update(current.text, false);
          }
          return;
        }
        if (event.state === 'recording') {
          if (
            !active.current ||
            active.current.ended ||
            active.current.stopping
          )
            return;
          setOpeningMic(false);
          setRecording(true);
          Native.keepAwake(true);
        }
        if (event.state === 'processing') {
          if (active.current) active.current.stopping = true;
          setRecording(false);
          setOpeningMic(false);
          levelRef.current = 0;
        }
        if (event.state === 'error') {
          if (active.current) active.current.stopping = true;
          setRecording(false);
          setOpeningMic(false);
          levelRef.current = 0;
          failCurrent('speechFailed');
        }
        if (event.state === 'stopped') {
          const current = active.current;
          if (current && !current.ended) {
            finish(current.text);
          }
          setRecording(false);
          levelRef.current = 0;
          Native.keepAwake(false);
        }
      },
    );
    return () => subscription.remove();
  }, [failCurrent, finish, showNotice]);

  useEffect(() => {
    if (!hydrated) {
      return;
    }
    const transport = settings.transport;
    let disposed = false;
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
    let heartbeat: ReturnType<typeof setInterval> | undefined;
    let attempts = 0;
    let unauthorized = false;
    let pingNonce = 0;
    let attempting = false;
    const hasTarget = transport === 'lan' ? !!computer : !!bluetoothComputer;
    Native.disconnect();
    Native.disconnectBluetooth();
    setLatency(null);
    setConnection(hasTarget ? 'connecting' : 'disconnected');
    const sendPing = () => {
      pingNonce = Date.now();
      const payload = JSON.stringify({ type: 'ping', nonce: pingNonce });
      void (
        transport === 'lan'
          ? Native.send(payload)
          : Native.sendBluetooth(payload)
      ).catch(() => {});
    };
    const schedule = (immediate = false) => {
      if (disposed || !hasTarget || unauthorized || reconnectTimer) {
        return;
      }
      reconnectTimer = setTimeout(
        () => {
          reconnectTimer = undefined;
          void attempt();
        },
        immediate ? 0 : Math.min(400 * 1.7 ** attempts++, 6000),
      );
    };
    const attempt = async () => {
      if (disposed || !hasTarget || unauthorized || attempting) {
        return;
      }
      attempting = true;
      try {
        if (transport === 'lan' && computer) {
          if (attempts >= 1 && attempts % 3 === 1) {
            const found = await Native.discover();
            if (disposed) {
              return;
            }
            const same = found.find(
              item =>
                item.id === computer.id &&
                item.fingerprint === computer.fingerprint,
            );
            if (same && same.host !== computer.host) {
              const next = { ...computer, host: same.host };
              setComputer(next);
              await store.set('computer', next);
              return;
            }
          }
          if (!disposed) {
            await Native.connect(JSON.stringify(computer));
          }
        } else if (bluetoothComputer) {
          await Native.connectBluetooth(bluetoothComputer.address);
        }
      } catch {
        schedule();
      } finally {
        attempting = false;
      }
    };
    const subscription = events.addListener(
      'PhoneMicTransport',
      (event: TransportEvent) => {
        if (disposed || event.transport !== transport) {
          return;
        }
        if (event.type === 'message') {
          try {
            const reply = JSON.parse(event.data || '{}') as Reply;
            if (
              reply.type === 'pong' &&
              reply.nonce === pingNonce &&
              transport === 'lan'
            ) {
              setLatency(Date.now() - pingNonce);
            }
            active.current?.session?.receive(reply);
          } catch {
            /* Ignore malformed replies without disturbing saved text. */
          }
          return;
        }
        if (!event.state) {
          return;
        }
        state.current.connection = event.state;
        setConnection(event.state);
        if (event.state === 'connected') {
          attempts = 0;
          clearTimeout(reconnectTimer);
          reconnectTimer = undefined;
          clearInterval(heartbeat);
          sendPing();
          heartbeat = setInterval(sendPing, 15000);
          const current = active.current;
          if (
            current &&
            !current.failed &&
            (current.ended || current.settings.mode === 'hold')
          )
            deliver.current(current);
        } else if (
          event.state === 'disconnected' ||
          event.state === 'unauthorized'
        ) {
          clearInterval(heartbeat);
          setLatency(null);
          if (active.current?.session) {
            const current = active.current;
            if (current.attempted) failCurrent('connectionLost');
            else {
              current.session?.dispose();
              current.session = undefined;
              if (current.ended) deliver.current(current);
            }
          }
          if (event.state === 'unauthorized') {
            unauthorized = true;
            if (transport === 'lan' && computer) {
              void Native.pair(computer.host, computer.fingerprint, '')
                .then(async target => {
                  if (disposed) return;
                  await store.set('computer', target);
                  if (!disposed) setComputer(target);
                })
                .catch(() => {
                  if (!disposed) showNotice('receiverLinked');
                });
            } else showNotice('connectionLost');
          } else {
            if (event.message === 'Network changed') {
              attempts = 1;
              clearTimeout(reconnectTimer);
              reconnectTimer = undefined;
              schedule(true);
            } else schedule();
          }
        }
      },
    );
    const appSubscription = AppState.addEventListener('change', value => {
      if (value === 'active' && state.current.connection !== 'connected') {
        clearTimeout(reconnectTimer);
        reconnectTimer = undefined;
        void attempt();
      } else if (value === 'active') {
        sendPing();
      } else {
        if (active.current?.origin !== 'headset') {
          held.current = false;
          if (active.current) active.current.stopping = true;
          setRecording(false);
          setOpeningMic(false);
          levelRef.current = 0;
          Native.stopSpeech();
        }
      }
    });
    void attempt();
    return () => {
      disposed = true;
      clearTimeout(reconnectTimer);
      clearInterval(heartbeat);
      subscription.remove();
      appSubscription.remove();
      Native.disconnect();
      Native.disconnectBluetooth();
    };
  }, [
    hydrated,
    settings.transport,
    computer,
    bluetoothComputer,
    failCurrent,
    showNotice,
  ]);

  const updateSettings = useCallback(
    (patch: Partial<Settings>) => {
      if (active.current || starting.current || savingVoiceProfile.current) {
        showNotice('alreadyRecording');
        return;
      }
      const next = {
        ...state.current.settings,
        ...patch,
        language: 'auto' as const,
      };
      if (next.voiceFilter && !isCompatibleVoiceProfile(voiceProfile)) {
        next.voiceFilter = false;
        setVoiceProfileIncompatible(true);
        showNotice('voiceprintIncompatible');
      }
      setSettings(next);
      void store.set('settings', next).catch(() => showNotice('storageFailed'));
    },
    [showNotice, voiceProfile],
  );
  const microphonePermission = useCallback(
    async (isCancelled: () => boolean = () => false) => {
      if (
        await PermissionsAndroid.check(
          PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
        )
      ) {
        return !isCancelled();
      }
      if (isCancelled()) return false;
      const result = await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
      );
      if (isCancelled()) return false;
      if (result === PermissionsAndroid.RESULTS.GRANTED) {
        return true;
      }
      const text = translator(state.current.settings.uiLanguage);
      Alert.alert(text('errorTitle'), text('microphonePermission'), [
        { text: text('cancel'), style: 'cancel' },
        {
          text: text('openSettings'),
          onPress: () => {
            void Linking.openSettings();
          },
        },
      ]);
      return false;
    },
    [],
  );
  const start = useCallback(
    async (fromHold: boolean, origin?: 'headset') => {
      if (
        active.current ||
        starting.current ||
        enrollment.current ||
        savingVoiceProfile.current ||
        !state.current.modelReady ||
        !state.current.hydrated
      ) {
        return;
      }
      const epoch = ++startEpoch.current;
      starting.current = true;
      try {
        if (
          !(await microphonePermission()) ||
          epoch !== startEpoch.current ||
          (fromHold && !held.current)
        ) {
          return;
        }
        const currentSettings = {
          ...state.current.settings,
          afterInput:
            state.current.settings.mode === 'toggle'
              ? ('none' as const)
              : state.current.settings.afterInput,
        };
        const incompatibleVoice =
          currentSettings.voiceFilter &&
          !isCompatibleVoiceProfile(voiceProfile);
        if (incompatibleVoice) {
          currentSettings.voiceFilter = false;
          const next = { ...state.current.settings, voiceFilter: false };
          setSettings(next);
          setVoiceProfileIncompatible(true);
          void store
            .set('settings', next)
            .catch(() => showNotice('storageFailed'));
        }
        const current: ActiveDictation = {
          id: `${Date.now().toString(36)}-${Math.random()
            .toString(36)
            .slice(2, 14)}`,
          started: Date.now(),
          settings: currentSettings,
          text: '',
          ended: false,
          stopping: false,
          failed: false,
          acknowledged: false,
          attempted: false,
          origin,
        };
        active.current = current;
        clearTimeout(recordingLimitTimer.current);
        recordingLimitTimer.current = setTimeout(() => {
          if (active.current !== current || current.ended || current.stopping)
            return;
          current.stopping = true;
          setRecording(false);
          setOpeningMic(false);
          levelRef.current = 0;
          if (current.settings.haptics) Native.haptic();
          Native.stopSpeech();
          showNotice('recordingLimit');
        }, 600000);
        setNotice(null);
        if (incompatibleVoice) showNotice('voiceprintIncompatible');
        setPreview('');
        setPreviewVisible(false);
        clearTimeout(previewTimer.current);
        levelRef.current = 0;
        setBusy(true);
        setOpeningMic(true);
        setRecording(true);
        if (currentSettings.haptics && origin === 'headset') {
          Native.haptic();
        }
        if (state.current.connection === 'connected') {
          current.session = createSession(current);
          current.session.begin();
        }
        try {
          await Native.startSpeech(currentSettings.language, current.id, {
            streaming: currentSettings.mode === 'hold',
            preview: true,
            hotwords: currentSettings.hotwords,
            background: origin === 'headset',
            ...(currentSettings.voiceFilter && voiceProfile
              ? {
                  speakerEmbedding: voiceProfile.embedding,
                  ...(voiceProfile.templates
                    ? { speakerTemplates: voiceProfile.templates }
                    : {}),
                }
              : {}),
          });
          if (active.current === current && fromHold && !held.current) {
            Native.stopSpeech();
          }
        } catch (error) {
          if (active.current !== current) return;
          if ((error as { code?: string })?.code !== 'CANCELLED') {
            failCurrent('speechFailed');
          }
          finish(current.text);
        }
      } finally {
        if (epoch === startEpoch.current) {
          setOpeningMic(false);
          starting.current = false;
        }
      }
    },
    [
      createSession,
      failCurrent,
      finish,
      microphonePermission,
      voiceProfile,
      showNotice,
    ],
  );
  const pressIn = useCallback(() => {
    if (state.current.settings.mode === 'hold') {
      held.current = true;
      void start(true);
    }
  }, [start]);
  const requestStop = useCallback((action?: CompletionAction) => {
    const current = active.current;
    if (current && !current.ended && !current.stopping) {
      current.stopping = true;
      setRecording(false);
      setOpeningMic(false);
      levelRef.current = 0;
      clearTimeout(recordingLimitTimer.current);
      if (action !== undefined)
        current.settings = { ...current.settings, afterInput: action };
      current.session?.setShortcut(completionShortcut(current.settings));
      if (current.settings.haptics) Native.haptic();
      Native.stopSpeech();
    }
  }, []);
  const discardRecording = useCallback(() => {
    const current = active.current;
    if (!current || current.ended) return;
    current.failed = true;
    current.stopping = true;
    current.ended = true;
    active.current = null;
    startEpoch.current++;
    starting.current = false;
    held.current = false;
    headsetHeld.current = false;
    clearTimeout(deliveryTimer.current);
    deliveryTimer.current = undefined;
    clearTimeout(previewTimer.current);
    clearTimeout(recordingLimitTimer.current);
    current.session?.cancel();
    Native.cancelSpeech();
    Native.keepAwake(false);
    if (current.settings.haptics) Native.haptic();
    setRecording(false);
    setOpeningMic(false);
    setBusy(false);
    levelRef.current = 0;
    setPreview('');
    setPreviewVisible(false);
    showNotice('recordingDiscarded');
  }, [showNotice]);
  const pressOut = useCallback(() => {
    if (state.current.settings.mode === 'hold') {
      held.current = false;
      requestStop();
    }
  }, [requestStop]);
  const press = useCallback(() => {
    if (state.current.settings.mode === 'toggle') {
      if (active.current && !active.current.ended) {
        requestStop('none');
      } else {
        void start(false);
      }
    }
  }, [requestStop, start]);
  const gestureHaptic = useCallback(() => {
    if (state.current.settings.haptics) Native.haptic();
  }, []);
  useEffect(() => {
    const subscription = events.addListener(
      'PhoneMicHeadset',
      (event: { action: 'down' | 'up' | 'toggle' | 'start' | 'stop' }) => {
        if (!state.current.settings.headsetControls) return;
        const hold = state.current.settings.mode === 'hold';
        const toggle = () => {
          const current = active.current;
          if (current?.stopping || current?.ended) {
            if (state.current.settings.haptics) Native.haptic();
            showNotice(current.ended ? 'sendingText' : 'processingText');
          } else if (current) requestStop(hold ? undefined : 'none');
          else void start(false, 'headset');
        };
        if (event.action === 'stop') {
          headsetHeld.current = false;
          held.current = false;
          requestStop(hold ? undefined : 'none');
          return;
        }
        if (event.action === 'start') {
          if (!active.current && !starting.current) {
            if (hold) showNotice('headsetTapOnly');
            void start(false, 'headset');
          }
          return;
        }
        if (event.action === 'down' && hold) {
          if (!headsetHeld.current) {
            headsetHeld.current = true;
            held.current = true;
            void start(true, 'headset');
          }
        } else if (event.action === 'up' && hold) {
          if (headsetHeld.current) {
            headsetHeld.current = false;
            held.current = false;
            requestStop();
          }
        } else if (event.action === 'down') {
          headsetHeld.current = true;
          toggle();
        } else if (event.action === 'up' && headsetHeld.current) {
          headsetHeld.current = false;
        } else if (event.action === 'up' || event.action === 'toggle') {
          if (event.action === 'toggle') {
            headsetHeld.current = false;
            held.current = false;
          }
          if (event.action === 'toggle' && hold) showNotice('headsetTapOnly');
          toggle();
        }
      },
    );
    return () => subscription.remove();
  }, [requestStop, start, showNotice]);
  const pairComputer = useCallback(
    async (candidate: Computer, code: string) => {
      const paired = await Native.pair(
        candidate.host,
        candidate.fingerprint,
        code,
      );
      await store.set('computer', paired);
      setComputer(paired);
      if (state.current.settings.transport !== 'lan') {
        updateSettings({ transport: 'lan' });
      }
    },
    [updateSettings],
  );
  const selectBluetooth = useCallback(
    async (candidate: BluetoothComputer) => {
      await store.set('bluetoothComputer', candidate);
      setBluetoothComputer(candidate);
      updateSettings({ transport: 'bluetooth' });
    },
    [updateSettings],
  );
  const forgetComputer = useCallback(() => {
    if (state.current.settings.transport === 'lan') {
      setComputer(null);
      void store.set('computer', null).catch(() => showNotice('storageFailed'));
    } else {
      setBluetoothComputer(null);
      void store
        .set('bluetoothComputer', null)
        .catch(() => showNotice('storageFailed'));
    }
  }, [showNotice]);
  const resend = useCallback(
    (entry: HistoryEntry) => {
      if (active.current || starting.current) {
        showNotice('alreadyRecording');
        return;
      }
      if (state.current.connection !== 'connected') {
        showNotice('connectionLost');
        return;
      }
      if (
        state.current.settings.transport === 'bluetooth' &&
        /[^\x09\x0a\x20-\x7e]/.test(entry.text)
      ) {
        showNotice('bluetoothNote');
        return;
      }
      const current: ActiveDictation = {
        id: `${Date.now().toString(36)}-${Math.random()
          .toString(36)
          .slice(2, 14)}`,
        started: Date.now(),
        settings: { ...state.current.settings, mode: 'toggle' },
        text: entry.text,
        ended: true,
        stopping: true,
        failed: false,
        acknowledged: false,
        attempted: true,
      };
      active.current = current;
      setBusy(true);
      setPreview(entry.text);
      markHistory(current);
      current.session = createSession(current);
      current.session.begin();
      current.session.update(entry.text, true);
    },
    [createSession, markHistory, showNotice],
  );
  const ensureBluetoothPermission = useCallback(async () => {
    if (Number(Platform.Version) < 31) {
      return true;
    }
    const result = await PermissionsAndroid.requestMultiple([
      PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
      PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE,
    ]);
    const granted = Object.values(result).every(
      value => value === PermissionsAndroid.RESULTS.GRANTED,
    );
    if (!granted) {
      showNotice('bluetoothFailed');
    }
    return granted;
  }, [showNotice]);
  const enrollVoiceprint = useCallback((): Promise<VoiceProfile> => {
    if (
      active.current ||
      starting.current ||
      enrollment.current ||
      savingVoiceProfile.current
    )
      return Promise.reject(new Error('BUSY'));
    const task: EnrollmentTask = {
      cancelled: false,
      nativeStarted: false,
    };
    enrollment.current = task;
    const cancelled = () => task.cancelled || enrollment.current !== task;
    task.completion = (async () => {
      try {
        const permitted = await microphonePermission(cancelled);
        if (cancelled()) throw new Error('CANCELLED');
        if (!permitted) throw new Error('MIC_PERMISSION');
        if (active.current || starting.current) throw new Error('BUSY');
        task.nativeStarted = true;
        Native.keepAwake(true);
        const profile = await Native.startVoiceprint();
        if (cancelled()) throw new Error('CANCELLED');
        return profile;
      } finally {
        if (task.nativeStarted) Native.keepAwake(false);
        if (enrollment.current === task) enrollment.current = null;
      }
    })();
    return task.completion;
  }, [microphonePermission]);
  const cancelVoiceprint = useCallback(async () => {
    const task = enrollment.current;
    if (!task) return;
    task.cancelled = true;
    if (!task.nativeStarted) {
      // A system permission dialog must not trap the user on this page.
      if (enrollment.current === task) enrollment.current = null;
      return;
    }
    Native.cancelVoiceprint();
    await task.completion?.catch(() => {});
  }, []);
  const saveVoiceprint = useCallback(
    async (profile: VoiceProfile) => {
      if (!isCompatibleVoiceProfile(profile)) {
        showNotice('voiceprintIncompatible');
        throw new Error('VOICEPRINT_INCOMPATIBLE');
      }
      if (active.current || starting.current || savingVoiceProfile.current)
        throw new Error('BUSY');
      savingVoiceProfile.current = true;
      try {
        await store.set('voiceProfile', profile);
        setVoiceProfile(profile);
        setVoiceProfileIncompatible(false);
        const next = { ...state.current.settings, voiceFilter: true };
        await store.set('settings', next);
        setSettings(next);
      } finally {
        savingVoiceProfile.current = false;
      }
    },
    [showNotice],
  );
  return {
    settings,
    updateSettings,
    computer,
    bluetoothComputer,
    pairComputer,
    selectBluetooth,
    forgetComputer,
    history,
    voiceProfile,
    voiceProfileIncompatible,
    enrollVoiceprint,
    cancelVoiceprint,
    saveVoiceprint,
    deleteVoiceprint: async () => {
      await store.set('voiceProfile', null);
      setVoiceProfile(null);
      setVoiceProfileIncompatible(false);
      updateSettings({ voiceFilter: false });
    },
    hydrated,
    modelReady,
    modelError,
    connection,
    latency,
    preview,
    previewVisible,
    recording,
    openingMic,
    sending: busy && !recording && !!active.current?.ended,
    busy,
    levelRef,
    notice,
    t,
    pressIn,
    pressOut,
    press,
    finishWithAction: requestStop,
    discardRecording,
    gestureHaptic,
    showNotice,
    ensureBluetoothPermission,
    resend,
    clearHistory: () => saveHistory(() => []),
    deleteHistory: (id: string) =>
      saveHistory(old => old.filter(item => item.id !== id)),
    copy: (text: string) => {
      Native.copyText(text);
      showNotice('copied');
    },
  };
}
export type PhoneMicController = ReturnType<typeof usePhoneMic>;
