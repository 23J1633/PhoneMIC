import React, { useState } from 'react';
import { Modal, Pressable, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { IconButton } from './components';
import type { Palette } from './theme';
import type { PhoneMicController } from './usePhoneMic';
import { s } from './uiStyles';

export function normalizeHotwords(value: string): string {
  return [
    ...new Set(
      value
        .split(/[,，;；\n]+/)
        .map(word => word.trim().slice(0, 40))
        .filter(Boolean),
    ),
  ]
    .slice(0, 30)
    .join(', ')
    .slice(0, 300);
}

export function VocabularyEditor({
  c,
  p,
  close,
}: {
  c: PhoneMicController;
  p: Palette;
  close: () => void;
}) {
  const [value, setValue] = useState(c.settings.hotwords);
  return (
    <Modal visible animationType="slide" onRequestClose={close}>
      <SafeAreaView style={{ flex: 1, backgroundColor: p.background }}>
        <View style={s.header}>
          <IconButton name="back" label={c.t('back')} onPress={close} p={p} />
          <Text style={{ flex: 1, fontSize: 16, color: p.ink }}>
            {c.t('hotwords')}
          </Text>
          <Pressable
            accessibilityRole="button"
            onPress={() => {
              c.updateSettings({ hotwords: normalizeHotwords(value) });
              close();
            }}
            style={{ padding: 16 }}
          >
            <Text style={{ fontSize: 14, color: p.accent }}>{c.t('done')}</Text>
          </Pressable>
        </View>
        <View style={[s.content, { gap: 20 }]}>
          <Text style={{ color: p.secondary, fontSize: 13, lineHeight: 22 }}>
            {c.t('hotwordsHint')}
          </Text>
          <TextInput
            accessibilityLabel={c.t('hotwords')}
            multiline
            value={value}
            onChangeText={setValue}
            maxLength={600}
            autoCapitalize="none"
            autoCorrect={false}
            textAlignVertical="top"
            placeholder={c.t('hotwordsPlaceholder')}
            placeholderTextColor={p.faint}
            style={{
              color: p.ink,
              fontSize: 17,
              lineHeight: 28,
              minHeight: 180,
              borderBottomWidth: 0.5,
              borderBottomColor: p.line,
              paddingVertical: 12,
            }}
          />
        </View>
      </SafeAreaView>
    </Modal>
  );
}
