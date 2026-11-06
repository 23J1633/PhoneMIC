import React, { useRef, useState } from 'react';
import { Alert, Modal, ScrollView, StatusBar, Text, View } from 'react-native';
import {
  SafeAreaProvider,
  SafeAreaView,
  useSafeAreaInsets,
} from 'react-native-safe-area-context';
import { IconButton, MicButton, VoiceWaveform } from './components';
import { usePalette } from './theme';
import { usePhoneMic } from './usePhoneMic';
import { SettingsScreen } from './SettingsScreen';
import { DevicesScreen } from './DevicesScreen';
import { HistoryScreen } from './HistoryScreen';
import { s } from './uiStyles';
type Screen = 'settings' | 'devices' | 'history' | null;
const TranscriptPreview = React.memo(function TranscriptPreview({
  text,
  color,
  recording,
}: {
  text: string;
  color: string;
  recording: boolean;
}) {
  const scroll = useRef<ScrollView>(null);
  const following = useRef(true);
  const dragging = useRef(false);
  const contentHeight = useRef(0);
  return (
    <ScrollView
      ref={scroll}
      style={{ flexGrow: 0 }}
      showsVerticalScrollIndicator={false}
      scrollEventThrottle={64}
      onScrollBeginDrag={() => {
        dragging.current = true;
      }}
      onScrollEndDrag={() => {
        dragging.current = false;
      }}
      onMomentumScrollBegin={() => {
        dragging.current = true;
      }}
      onMomentumScrollEnd={() => {
        dragging.current = false;
      }}
      onScroll={({
        nativeEvent: { contentOffset, contentSize, layoutMeasurement },
      }) => {
        if (dragging.current)
          following.current =
            contentSize.height - layoutMeasurement.height - contentOffset.y <
            24;
      }}
      onContentSizeChange={(_, height) => {
        if (height === contentHeight.current) return;
        contentHeight.current = height;
        if (recording && following.current && !dragging.current)
          scroll.current?.scrollToEnd({ animated: true });
      }}
    >
      <Text selectable style={{ fontSize: 22, lineHeight: 34, color }}>
        {text}
      </Text>
    </ScrollView>
  );
});
function Shell() {
  const c = usePhoneMic();
  const p = usePalette(c.settings.theme);
  const insets = useSafeAreaInsets();
  const [screen, setScreen] = useState<Screen>(null);
  const label = c.recording
    ? c.t(c.settings.mode === 'toggle' ? 'tapStop' : 'release')
    : c.t(c.settings.mode);
  const notice =
    c.notice && (screen || c.notice !== 'copied') ? c.notice : null;
  const notification = notice ? (
    <View pointerEvents="none" style={[s.notice, { backgroundColor: p.ink }]}>
      <Text
        style={{
          color: p.background,
          fontSize: 12,
          lineHeight: 19,
          textAlign: 'center',
        }}
      >
        {c.t(notice)}
      </Text>
    </View>
  ) : null;
  return (
    <SafeAreaView
      edges={['top', 'bottom']}
      style={{ flex: 1, backgroundColor: p.background }}
    >
      <StatusBar
        barStyle={p.dark ? 'light-content' : 'dark-content'}
        backgroundColor={p.background}
      />
      <View
        style={{
          position: 'absolute',
          right: 18,
          top: insets.top + 10,
          zIndex: 1,
          flexDirection: 'row',
          alignItems: 'center',
        }}
      >
        <View
          accessible
          accessibilityRole="image"
          accessibilityLiveRegion="polite"
          accessibilityLabel={`${c.t('connect')} · ${c.t(
            c.connection === 'connected'
              ? 'connected'
              : c.connection === 'connecting'
              ? 'connecting'
              : 'disconnected',
          )}`}
          pointerEvents="none"
          style={{
            width: 7,
            height: 7,
            borderRadius: 3.5,
            marginRight: 9,
            backgroundColor:
              c.connection === 'connected'
                ? p.connectionOnline
                : p.connectionOffline,
          }}
        />
        <IconButton
          p={p}
          name="settings"
          label={c.t('settings')}
          onPress={() => setScreen('settings')}
        />
      </View>
      <View style={{ flex: 1, paddingHorizontal: 34 }}>
        <View
          style={{
            flex: 1,
            justifyContent: 'flex-end',
            paddingTop: 76,
            paddingBottom: 36,
          }}
        >
          {(c.recording || c.busy || c.previewVisible) &&
            c.preview.trim().length > 0 && (
              <TranscriptPreview
                text={c.preview}
                color={p.ink}
                recording={c.recording}
              />
            )}
        </View>
        <View
          style={{
            height: 180,
            alignItems: 'center',
            justifyContent: 'space-between',
            paddingVertical: 8,
          }}
        >
          <VoiceWaveform
            p={p}
            recording={c.recording}
            levelRef={c.levelRef}
            visible={screen === null}
            label={c.t('waveform')}
          />
          <View style={{ width: 96, height: 96 }}>
            <MicButton
              p={p}
              recording={c.recording}
              disabled={
                !c.modelReady || (c.busy && !c.recording) || c.clearingInput
              }
              loading={
                !c.modelReady || (c.busy && !c.recording) || c.clearingInput
              }
              label={`${c.t('micAccessibility')} · ${label}`}
              pressIn={c.pressIn}
              pressOut={c.pressOut}
              press={c.press}
              mode={c.settings.mode}
              guideLabel={c.t('guideAction')}
              sendLabel={c.t('sendAction')}
              clearLabel={c.t('clearRecording')}
              clearInputLabel={c.t('clearInput')}
              prepareInputClear={c.prepareInputClear}
              clearInput={c.clearInput}
              cancelInputClear={c.cancelInputClear}
              finishWithAction={c.finishWithAction}
              discardRecording={c.discardRecording}
              haptic={c.gestureHaptic}
            />
            {(!c.modelReady || c.openingMic || (c.busy && !c.recording)) && (
              <Text
                accessibilityLiveRegion="polite"
                style={{
                  position: 'absolute',
                  top: 120,
                  left: -110,
                  right: -110,
                  textAlign: 'center',
                  fontSize: 12,
                  lineHeight: 19,
                  color: p.secondary,
                }}
              >
                {c.t(
                  c.modelError
                    ? 'modelFailed'
                    : !c.modelReady
                    ? 'modelLoading'
                    : c.openingMic
                    ? 'microphoneOpening'
                    : c.sending
                    ? c.connection === 'connected'
                      ? 'sendingText'
                      : 'waitingForComputer'
                    : 'processingText',
                )}
              </Text>
            )}
          </View>
        </View>
        <View style={{ flex: 1 }} />
      </View>
      <Modal
        visible={screen !== null}
        animationType="slide"
        presentationStyle="fullScreen"
        statusBarTranslucent
        onRequestClose={() =>
          setScreen(screen === 'settings' ? null : 'settings')
        }
      >
        <SafeAreaView style={{ flex: 1, backgroundColor: p.background }}>
          <StatusBar barStyle={p.dark ? 'light-content' : 'dark-content'} />
          <View style={s.header}>
            <IconButton
              p={p}
              name="back"
              label={c.t('back')}
              onPress={() =>
                setScreen(screen === 'settings' ? null : 'settings')
              }
            />
            <Text style={{ fontSize: 16, fontWeight: '500', color: p.ink }}>
              {screen ? c.t(screen === 'devices' ? 'connect' : screen) : ''}
            </Text>
            <View style={{ flex: 1 }} />
            {screen === 'history' && c.history.length > 0 && (
              <IconButton
                p={p}
                name="more"
                label={c.t('clearHistory')}
                onPress={() =>
                  Alert.alert(c.t('clearHistory'), c.t('clearConfirm'), [
                    { text: c.t('cancel'), style: 'cancel' },
                    {
                      text: c.t('confirm'),
                      style: 'destructive',
                      onPress: c.clearHistory,
                    },
                  ])
                }
              />
            )}
          </View>
          {screen === 'settings' && (
            <SettingsScreen
              c={c}
              p={p}
              devices={() =>
                c.busy ? c.showNotice('alreadyRecording') : setScreen('devices')
              }
              history={() => setScreen('history')}
            />
          )}
          {screen === 'devices' && (
            <DevicesScreen c={c} p={p} close={() => setScreen('settings')} />
          )}
          {screen === 'history' && (
            <HistoryScreen c={c} p={p} close={() => setScreen(null)} />
          )}
          {notification}
        </SafeAreaView>
      </Modal>
      {!screen && notification}
    </SafeAreaView>
  );
}
export default function App() {
  return (
    <SafeAreaProvider>
      <Shell />
    </SafeAreaProvider>
  );
}
