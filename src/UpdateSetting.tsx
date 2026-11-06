import React from 'react';
import {
  ActivityIndicator,
  Linking,
  Pressable,
  Text,
  View,
} from 'react-native';
import { Icon } from './components';
import { LATEST_RELEASE_URL } from './appUpdates';
import { useAppUpdate } from './useAppUpdate';
import type { PhoneMicController } from './usePhoneMic';
import type { Palette } from './theme';
import { s } from './uiStyles';

export function UpdateSetting({ c, p }: { c: PhoneMicController; p: Palette }) {
  const { currentVersion, result, check } = useAppUpdate();
  const status =
    result.status === 'checking'
      ? c.t('updateChecking')
      : result.status === 'available'
      ? `${c.t('updateAvailable')} ${result.version}`
      : result.status === 'current'
      ? c.t('updateCurrent')
      : result.status === 'unpublished'
      ? c.t('updateUnpublished')
      : c.t('updateFailed');
  return (
    <View style={{ marginTop: 24 }}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`${c.t('checkUpdates')} · ${status}`}
        accessibilityState={{
          disabled: result.status === 'checking',
          busy: result.status === 'checking',
        }}
        disabled={result.status === 'checking'}
        onPress={() => {
          void check();
        }}
        style={[s.setting, { borderBottomColor: p.line }]}
      >
        <View style={{ gap: 6 }}>
          <Text style={{ fontSize: 14, color: p.ink }}>
            {c.t('checkUpdates')}
          </Text>
          <Text style={{ fontSize: 11, color: p.faint }}>
            {c.t('currentVersion')} {currentVersion}
          </Text>
        </View>
        <View style={[s.row, { gap: 8, flexShrink: 1, marginLeft: 16 }]}>
          {result.status === 'checking' && (
            <ActivityIndicator size="small" color={p.faint} />
          )}
          <Text
            accessibilityLiveRegion="polite"
            style={{
              fontSize: 12,
              flexShrink: 1,
              color: result.status === 'available' ? p.accent : p.secondary,
            }}
          >
            {status}
          </Text>
        </View>
      </Pressable>
      <Pressable
        accessibilityRole="link"
        onPress={() => {
          void Linking.openURL(LATEST_RELEASE_URL).catch(() =>
            c.showNotice('updateOpenFailed'),
          );
        }}
        style={[
          s.row,
          { paddingVertical: 18, justifyContent: 'space-between' },
        ]}
      >
        <Text style={{ fontSize: 12, color: p.secondary }}>
          {c.t('viewLatestRelease')}
        </Text>
        <Icon name="arrow" size={13} color={p.faint} />
      </Pressable>
    </View>
  );
}
