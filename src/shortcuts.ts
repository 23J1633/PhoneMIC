import type { KeyboardShortcut } from './types';

export const modifierKeys = [
  { key: 'Control', label: 'Ctrl' },
  { key: 'Alt', label: 'Alt' },
  { key: 'Shift', label: 'Shift' },
  { key: 'Meta', label: 'Win' },
] as const;
export const shortcutKeys = [
  ['Enter', 'Enter'],
  ['Tab', 'Tab'],
  ['Space', 'Space'],
  ['Escape', 'Esc'],
  ['Backspace', '⌫'],
  ['Delete', 'Delete'],
  ['ArrowLeft', '←'],
  ['ArrowUp', '↑'],
  ['ArrowDown', '↓'],
  ['ArrowRight', '→'],
  ['Home', 'Home'],
  ['End', 'End'],
  ['PageUp', 'PgUp'],
  ['PageDown', 'PgDn'],
  ['Insert', 'Insert'],
  ['CapsLock', 'Caps'],
  ['PrintScreen', 'PrtSc'],
  ['Pause', 'Pause'],
  ['ScrollLock', 'ScrLk'],
  ['NumLock', 'NumLk'],
  ['ContextMenu', 'Menu'],
  ...Array.from({ length: 24 }, (_, i) => [`F${i + 1}`, `F${i + 1}`]),
  ...'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'.split('').map(key => [key, key]),
  ['Minus', '−'],
  ['Equal', '='],
  ['BracketLeft', '['],
  ['BracketRight', ']'],
  ['Backslash', '\\'],
  ['Semicolon', ';'],
  ['Quote', "'"],
  ['Backquote', '`'],
  ['Comma', ','],
  ['Period', '.'],
  ['Slash', '/'],
  ...Array.from({ length: 10 }, (_, i) => [`Numpad${i}`, `Num ${i}`]),
  ['NumpadEnter', 'Num ↵'],
  ['NumpadDecimal', 'Num .'],
  ['NumpadAdd', 'Num +'],
  ['NumpadSubtract', 'Num −'],
  ['NumpadMultiply', 'Num ×'],
  ['NumpadDivide', 'Num ÷'],
] as const;

export function formatShortcut(value: KeyboardShortcut): string {
  return [
    ...modifierKeys
      .filter(mod => value.modifiers.includes(mod.key))
      .map(mod => mod.label),
    shortcutKeys.find(([key]) => key === value.key)?.[1] || value.key,
  ].join(' + ');
}
export function completionShortcut(settings: {
  afterInput: string;
  sendShortcut: KeyboardShortcut;
  guideShortcut: KeyboardShortcut;
}): KeyboardShortcut | undefined {
  return settings.afterInput === 'send'
    ? settings.sendShortcut
    : settings.afterInput === 'guide'
    ? settings.guideShortcut
    : undefined;
}
