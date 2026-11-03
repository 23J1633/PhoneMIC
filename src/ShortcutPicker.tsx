import React, { useState } from 'react';
import { Modal, Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { IconButton } from './components';
import { formatShortcut, modifierKeys, shortcutKeys } from './shortcuts';
import type { KeyboardShortcut } from './types';
import type { Palette } from './theme';
import type { PhoneMicController } from './usePhoneMic';
import { s } from './uiStyles';

export function ShortcutPicker({
  title,
  value,
  p,
  c,
  close,
  save,
}: {
  title: string;
  value: KeyboardShortcut;
  p: Palette;
  c: PhoneMicController;
  close: () => void;
  save: (shortcut: KeyboardShortcut) => void;
}) {
  const [selected, setSelected] = useState<KeyboardShortcut>({
    key: value.key,
    modifiers: [...value.modifiers],
  });
  const keyStyle = (checked: boolean) => ({
    minHeight: 44,
    borderRadius: 8,
    borderWidth: 0.5,
    borderColor: checked ? p.accent : p.line,
    backgroundColor: checked ? p.accentSoft : p.surface,
    justifyContent: 'center' as const,
    alignItems: 'center' as const,
  });
  return (
    <Modal visible animationType="slide" onRequestClose={close}>
      <SafeAreaView style={{ flex: 1, backgroundColor: p.background }}>
        <View style={s.header}>
          <IconButton name="back" label={c.t('back')} onPress={close} p={p} />
          <Text style={{ flex: 1, fontSize: 16, color: p.ink }}>{title}</Text>
          <Pressable
            accessibilityRole="button"
            onPress={() => save(selected)}
            style={{ padding: 16 }}
          >
            <Text style={{ color: p.accent, fontSize: 14 }}>{c.t('done')}</Text>
          </Pressable>
        </View>
        <ScrollView contentContainerStyle={[s.content, { gap: 22 }]}>
          <Text
            accessibilityLiveRegion="polite"
            style={{ fontSize: 24, color: p.ink, paddingVertical: 16 }}
          >
            {formatShortcut(selected)}
          </Text>
          <Text style={{ fontSize: 12, color: p.secondary }}>
            {c.t('shortcutHint')}
          </Text>
          <View style={{ flexDirection: 'row', gap: 8 }}>
            {modifierKeys.map(mod => {
              const checked = selected.modifiers.includes(mod.key);
              return (
                <Pressable
                  key={mod.key}
                  accessibilityRole="checkbox"
                  accessibilityLabel={mod.label}
                  accessibilityState={{ checked }}
                  onPress={() =>
                    setSelected(old => ({
                      ...old,
                      modifiers: checked
                        ? old.modifiers.filter(key => key !== mod.key)
                        : [...old.modifiers, mod.key],
                    }))
                  }
                  style={[keyStyle(checked), { flex: 1 }]}
                >
                  <Text
                    style={{
                      fontSize: 13,
                      color: checked ? p.accent : p.secondary,
                    }}
                  >
                    {mod.label}
                  </Text>
                </Pressable>
              );
            })}
          </View>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6 }}>
            {shortcutKeys.map(([key, label]) => {
              const checked = selected.key === key;
              return (
                <Pressable
                  key={key}
                  accessibilityRole="radio"
                  accessibilityLabel={key}
                  accessibilityState={{ checked }}
                  onPress={() => setSelected(old => ({ ...old, key }))}
                  style={[keyStyle(checked), { width: '15%' }]}
                >
                  <Text
                    style={{
                      fontSize: 12,
                      color: checked ? p.accent : p.secondary,
                    }}
                  >
                    {label}
                  </Text>
                </Pressable>
              );
            })}
          </View>
        </ScrollView>
      </SafeAreaView>
    </Modal>
  );
}
