import React from 'react';
import { Alert, FlatList, Text, View } from 'react-native';
import { IconButton } from './components';
import type { Palette } from './theme';
import type { HistoryEntry } from './types';
import type { PhoneMicController } from './usePhoneMic';
import { s } from './uiStyles';
export function HistoryScreen({
  c,
  p,
  close,
}: {
  c: PhoneMicController;
  p: Palette;
  close: () => void;
}) {
  const resend = (entry: HistoryEntry) => {
    const send = () => {
      c.resend(entry);
      close();
    };
    if (entry.status === 'uncertain')
      Alert.alert(c.t('resend'), c.t('sendAgainConfirm'), [
        { text: c.t('cancel'), style: 'cancel' },
        { text: c.t('confirm'), onPress: send },
      ]);
    else send();
  };
  return (
    <FlatList
      data={c.history}
      keyExtractor={item => item.id}
      contentContainerStyle={[s.content, { flexGrow: 1 }]}
      ListEmptyComponent={
        <View
          style={{
            flex: 1,
            justifyContent: 'center',
            alignItems: 'center',
            paddingBottom: 60,
          }}
        >
          <Text style={{ color: p.faint, fontSize: 14 }}>
            {c.t('historyEmpty')}
          </Text>
        </View>
      }
      renderItem={({ item }) => (
        <View
          style={{
            paddingVertical: 18,
            borderBottomWidth: 0.5,
            borderBottomColor: p.line,
          }}
        >
          <View style={s.between}>
            <Text style={{ fontSize: 11, color: p.faint }}>
              {new Date(item.createdAt).toLocaleString(
                c.settings.uiLanguage === 'zh' ? 'zh-CN' : 'en-US',
                {
                  month: 'short',
                  day: 'numeric',
                  hour: '2-digit',
                  minute: '2-digit',
                },
              )}
            </Text>
            <View style={s.row}>
              <IconButton
                p={p}
                name="copy"
                label={c.t('copy')}
                onPress={() => c.copy(item.text)}
              />
              <IconButton
                p={p}
                name="send"
                label={c.t('resend')}
                onPress={() => resend(item)}
              />
              <IconButton
                p={p}
                name="trash"
                label={c.t('remove')}
                onPress={() => c.deleteHistory(item.id)}
              />
            </View>
          </View>
          <Text
            selectable
            style={{
              color: p.ink,
              fontSize: 16,
              lineHeight: 25,
              marginBottom: 9,
            }}
          >
            {item.text}
          </Text>
        </View>
      )}
    />
  );
}
