export type Language = 'auto' | 'zh-CN' | 'en-US';
export type UILanguage = 'zh' | 'en';
export type Transport = 'lan' | 'bluetooth';
export type ThemeMode = 'system' | 'light' | 'dark';
export type InputMode = 'hold' | 'toggle';
export type CompletionAction = 'none' | 'send' | 'guide';
export interface KeyboardShortcut {
  key: string;
  modifiers: ('Control' | 'Alt' | 'Shift' | 'Meta')[];
}
export interface Settings {
  language: Language;
  uiLanguage: UILanguage;
  transport: Transport;
  theme: ThemeMode;
  mode: InputMode;
  haptics: boolean;
  afterInput: CompletionAction;
  hotwords: string;
  voiceFilter: boolean;
  headsetControls: boolean;
  sendShortcut: KeyboardShortcut;
  guideShortcut: KeyboardShortcut;
}
export interface Computer {
  id: string;
  name: string;
  host: string;
  fingerprint: string;
  token?: string;
}
export interface BluetoothComputer {
  id: string;
  name: string;
  address: string;
}
export interface HistoryEntry {
  id: string;
  text: string;
  language: Language;
  mode: InputMode;
  createdAt: number;
  duration: number;
  status: 'sent' | 'saved' | 'uncertain';
  transport: Transport;
}
export interface VoiceEvent {
  type: 'state' | 'transcript' | 'level' | 'filtered' | 'inputSource';
  source?: 'headset' | 'phone';
  fallback?: boolean;
  state?:
    | 'loading'
    | 'ready'
    | 'recording'
    | 'processing'
    | 'stopped'
    | 'error';
  session?: string;
  message?: string;
  text?: string;
  final?: boolean;
  value?: number;
}
export interface VoiceProfile {
  model: string;
  embedding: number[];
  templates?: number[][];
  createdAt: number;
  durationMs: number;
}
export const VOICEPRINT_MODEL = 'campplus-zh-en-v1';
export function isCompatibleVoiceProfile(
  value: unknown,
): value is VoiceProfile {
  if (!value || typeof value !== 'object') return false;
  const profile = value as Partial<VoiceProfile>;
  const validVector = (vector: unknown, length?: number): vector is number[] =>
    Array.isArray(vector) &&
    vector.length > 0 &&
    vector.length <= 512 &&
    (length === undefined || vector.length === length) &&
    vector.every(
      item =>
        typeof item === 'number' &&
        Number.isFinite(item) &&
        Number.isFinite(Math.fround(item)),
    ) &&
    vector.some(item => Math.abs(item) > 0.00001);
  return (
    profile.model === VOICEPRINT_MODEL &&
    validVector(profile.embedding) &&
    (profile.templates === undefined ||
      (Array.isArray(profile.templates) &&
        profile.templates.length <= 8 &&
        profile.templates.every(vector =>
          validVector(vector, profile.embedding!.length),
        ))) &&
    typeof profile.createdAt === 'number' &&
    Number.isFinite(profile.createdAt) &&
    profile.createdAt >= 0 &&
    typeof profile.durationMs === 'number' &&
    Number.isFinite(profile.durationMs) &&
    profile.durationMs > 0
  );
}
export interface TransportEvent {
  type: 'status' | 'message';
  transport: Transport;
  state?: 'connecting' | 'connected' | 'disconnected' | 'unauthorized';
  message?: string;
  data?: string;
}
export interface Packet {
  type: 'begin' | 'text' | 'cancel';
  session: string;
  seq: number;
  text?: string;
  final?: boolean;
  shortcut?: KeyboardShortcut;
}
export interface Reply {
  type: 'hello' | 'pong' | 'ack' | 'error';
  session?: string;
  seq?: number;
  final?: boolean;
  message?: string;
  code?: string;
  nonce?: number;
}
export const defaultSettings: Settings = {
  language: 'auto',
  uiLanguage: 'zh',
  transport: 'lan',
  theme: 'system',
  mode: 'toggle',
  haptics: true,
  afterInput: 'none',
  hotwords: '',
  voiceFilter: false,
  headsetControls: true,
  sendShortcut: { key: 'Enter', modifiers: [] },
  guideShortcut: { key: 'Enter', modifiers: ['Control'] },
};
