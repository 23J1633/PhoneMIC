import { NativeEventEmitter, NativeModules } from 'react-native';
import type { BluetoothComputer, Computer, VoiceProfile } from './types';

interface PhoneMicModule {
  prepareSpeech(language: string): Promise<boolean>;
  startSpeech(
    language: string,
    session: string,
    options: {
      streaming: boolean;
      preview: boolean;
      hotwords: string;
      speakerEmbedding?: number[];
      speakerTemplates?: number[][];
      background?: boolean;
    },
  ): Promise<boolean>;
  startVoiceprint(): Promise<VoiceProfile>;
  cancelVoiceprint(): void;
  setHeadsetControls(enabled: boolean): void;
  stopSpeech(): void;
  cancelSpeech(): void;
  discover(): Promise<Computer[]>;
  probe(host: string): Promise<Computer>;
  pair(host: string, fingerprint: string, code: string): Promise<Computer>;
  connect(target: string): Promise<boolean>;
  disconnect(): void;
  send(payload: string): Promise<boolean>;
  bluetoothDevices(): Promise<BluetoothComputer[]>;
  connectBluetooth(address: string): Promise<boolean>;
  disconnectBluetooth(): void;
  sendBluetooth(payload: string): Promise<boolean>;
  makeDiscoverable(): Promise<boolean>;
  openBluetoothSettings(): void;
  copyText(text: string): void;
  haptic(): void;
  keepAwake(enabled: boolean): void;
  getStore(key: string): Promise<string | null>;
  setStore(key: string, value: string | null): Promise<boolean>;
  addListener(name: string): void;
  removeListeners(count: number): void;
}
export const Native = NativeModules.PhoneMic as PhoneMicModule;
export const events = new NativeEventEmitter(Native);
export const store = {
  async get<T>(key: string, fallback: T): Promise<T> {
    const raw = await Native.getStore(key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  },
  async set(key: string, value: unknown): Promise<void> {
    await Native.setStore(key, value == null ? null : JSON.stringify(value));
  },
};
