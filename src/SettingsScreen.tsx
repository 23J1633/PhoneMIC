import React, { useState } from 'react';
import { Pressable, ScrollView, Switch, Text, View } from 'react-native';
import { Icon, Options } from './components';
import type { Palette } from './theme';
import type { PhoneMicController } from './usePhoneMic';
import { s } from './uiStyles';
import { ShortcutPicker } from './ShortcutPicker';
import { formatShortcut } from './shortcuts';
import { VocabularyEditor } from './VocabularyEditor';
import { VoiceprintScreen } from './VoiceprintScreen';
import { UpdateSetting } from './UpdateSetting';
export function SettingsScreen({
  c,
  p,
  devices,
  history,
}: {
  c: PhoneMicController;
  p: Palette;
  devices: () => void;
  history: () => void;
}) {
  const [editingShortcut, setEditingShortcut] = useState<
    'sendShortcut' | 'guideShortcut' | null
  >(null);
  const [editingVocabulary, setEditingVocabulary] = useState(false);
  const [editingVoiceprint, setEditingVoiceprint] = useState(false);
  const computer =
    c.settings.transport === 'lan'
      ? c.computer?.name
      : c.bluetoothComputer?.name;
  return (
    <ScrollView contentContainerStyle={s.content}>
      <Pressable
        accessibilityRole="button"
        onPress={devices}
        style={[s.setting, { borderBottomColor: p.line }]}
      >
        <Text style={{ fontSize: 14, color: p.ink }}>{c.t('connection')}</Text>
        <View style={[s.row, { gap: 8, flexShrink: 1 }]}>
          <View
            style={{
              width: 4,
              height: 4,
              borderRadius: 2,
              backgroundColor:
                c.connection === 'connected' ? p.accent : p.faint,
            }}
          />
          <Text
            numberOfLines={1}
            style={{ fontSize: 12, color: p.secondary, maxWidth: 170 }}
          >
            {computer || c.t('disconnected')}
          </Text>
          <Icon name="arrow" size={13} color={p.faint} />
        </View>
      </Pressable>
      {c.settings.mode === 'hold' && (
        <View style={[s.setting, { borderBottomColor: p.line }]}>
          <Text style={{ fontSize: 14, color: p.ink }}>
            {c.t('afterHoldInput')}
          </Text>
          <Options
            p={p}
            value={c.settings.afterInput}
            options={[
              { value: 'none', label: c.t('typeOnly') },
              { value: 'send', label: c.t('sendAction') },
              { value: 'guide', label: c.t('guideAction') },
            ]}
            onChange={afterInput => c.updateSettings({ afterInput })}
          />
        </View>
      )}
      {(['sendShortcut', 'guideShortcut'] as const).map(key => (
        <Pressable
          key={key}
          accessibilityRole="button"
          accessibilityLabel={c.t(key)}
          onPress={() =>
            c.busy ? c.showNotice('alreadyRecording') : setEditingShortcut(key)
          }
          style={[s.setting, { borderBottomColor: p.line }]}
        >
          <Text style={{ fontSize: 14, color: p.ink }}>{c.t(key)}</Text>
          <View style={[s.row, { gap: 10 }]}>
            <Text style={{ fontSize: 12, color: p.secondary }}>
              {formatShortcut(c.settings[key])}
            </Text>
            <Icon name="arrow" size={13} color={p.faint} />
          </View>
        </Pressable>
      ))}
      <View style={[s.setting, { borderBottomColor: p.line }]}>
        <Text style={{ fontSize: 14, color: p.ink }}>{c.t('inputMode')}</Text>
        <Options
          p={p}
          value={c.settings.mode}
          options={[
            { value: 'hold', label: c.t('hold') },
            { value: 'toggle', label: c.t('toggle') },
          ]}
          onChange={mode => c.updateSettings({ mode })}
        />
      </View>
      <View style={[s.setting, { borderBottomColor: p.line }]}>
        <Text style={{ fontSize: 14, color: p.ink }}>
          {c.t('interfaceLanguage')}
        </Text>
        <Options
          p={p}
          value={c.settings.uiLanguage}
          options={[
            { value: 'zh', label: '中文' },
            { value: 'en', label: 'English' },
          ]}
          onChange={uiLanguage => c.updateSettings({ uiLanguage })}
        />
      </View>
      <View style={[s.setting, { borderBottomColor: p.line }]}>
        <Text style={{ fontSize: 14, color: p.ink }}>{c.t('appearance')}</Text>
        <Options
          p={p}
          value={c.settings.theme}
          options={[
            { value: 'system', label: c.t('system') },
            { value: 'light', label: c.t('light') },
            { value: 'dark', label: c.t('dark') },
          ]}
          onChange={theme => c.updateSettings({ theme })}
        />
      </View>
      <Pressable
        accessibilityRole="button"
        onPress={history}
        style={[s.setting, { borderBottomColor: p.line }]}
      >
        <Text style={{ fontSize: 14, color: p.ink }}>{c.t('history')}</Text>
        <View style={[s.row, { gap: 10 }]}>
          <Text style={{ fontSize: 12, color: p.faint }}>
            {c.history.length || ''}
          </Text>
          <Icon name="arrow" size={13} color={p.faint} />
        </View>
      </Pressable>
      <Pressable
        accessibilityRole="button"
        onPress={() =>
          c.busy ? c.showNotice('alreadyRecording') : setEditingVocabulary(true)
        }
        style={[s.setting, { borderBottomColor: p.line }]}
      >
        <Text style={{ fontSize: 14, color: p.ink }}>{c.t('hotwords')}</Text>
        <View style={[s.row, { gap: 10 }]}>
          <Text style={{ fontSize: 12, color: p.faint }}>
            {c.settings.hotwords
              ? c.settings.hotwords.split(',').length
              : c.t('optional')}
          </Text>
          <Icon name="arrow" size={13} color={p.faint} />
        </View>
      </Pressable>
      <Pressable
        accessibilityRole="button"
        onPress={() =>
          c.busy ? c.showNotice('alreadyRecording') : setEditingVoiceprint(true)
        }
        style={[s.setting, { borderBottomColor: p.line }]}
      >
        <Text style={{ fontSize: 14, color: p.ink }}>{c.t('voiceprint')}</Text>
        <View style={[s.row, { gap: 10 }]}>
          <Text style={{ fontSize: 12, color: p.faint }}>
            {c.t(
              c.settings.voiceFilter
                ? 'voiceprintEnabled'
                : c.voiceProfile
                ? 'voiceprintRegistered'
                : 'voiceprintMissing',
            )}
          </Text>
          <Icon name="arrow" size={13} color={p.faint} />
        </View>
      </Pressable>
      <View style={[s.setting, { borderBottomColor: p.line }]}>
        <Text style={{ fontSize: 14, color: p.ink }}>
          {c.t('headsetControls')}
        </Text>
        <Switch
          accessibilityLabel={c.t('headsetControls')}
          value={c.settings.headsetControls}
          onValueChange={headsetControls =>
            c.updateSettings({ headsetControls })
          }
          trackColor={{ true: p.accent, false: p.line }}
          thumbColor="#FFFFFF"
        />
      </View>
      <Text
        style={{ fontSize: 12, lineHeight: 21, color: p.faint, marginTop: 14 }}
      >
        {c.t('headsetHint')}
      </Text>
      <UpdateSetting c={c} p={p} />
      {editingVoiceprint && (
        <VoiceprintScreen
          c={c}
          p={p}
          close={() => setEditingVoiceprint(false)}
        />
      )}
      {editingVocabulary && (
        <VocabularyEditor
          c={c}
          p={p}
          close={() => setEditingVocabulary(false)}
        />
      )}
      {editingShortcut && (
        <ShortcutPicker
          title={c.t(editingShortcut)}
          value={c.settings[editingShortcut]}
          c={c}
          p={p}
          close={() => setEditingShortcut(null)}
          save={shortcut => {
            c.updateSettings({ [editingShortcut]: shortcut });
            setEditingShortcut(null);
          }}
        />
      )}
    </ScrollView>
  );
}
