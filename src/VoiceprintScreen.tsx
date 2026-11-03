import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Modal,
  Pressable,
  ScrollView,
  Switch,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { ActionButton, IconButton, VoiceWaveform } from './components';
import { events } from './native';
import type { PhoneMicController } from './usePhoneMic';
import type { Palette } from './theme';
import { s } from './uiStyles';
type EnrollmentPhase =
  | 'idle'
  | 'recording'
  | 'processing'
  | 'cancelling'
  | 'saving';

export function VoiceprintScreen({
  c,
  p,
  close,
}: {
  c: PhoneMicController;
  p: Palette;
  close: () => void;
}) {
  const cancelEnrollment = c.cancelVoiceprint;
  const [phase, setPhaseState] = useState<EnrollmentPhase>('idle');
  const phaseRef = useRef<EnrollmentPhase>('idle');
  const setPhase = useCallback((value: EnrollmentPhase) => {
    phaseRef.current = value;
    setPhaseState(value);
  }, []);
  const recording = phase === 'recording';
  const processing =
    phase === 'processing' || phase === 'cancelling' || phase === 'saving';
  const locked = phase === 'cancelling' || phase === 'saving';
  const [remaining, setRemaining] = useState(6);
  const [quietStage, setQuietStage] = useState(false);
  const levelRef = useRef(0);
  const [message, setMessage] = useState('');
  const generation = useRef({ value: 0 });
  const acceptProgress = useRef(false);
  const closing = useRef(false);
  useEffect(() => {
    const lifecycle = generation.current;
    const listener = events.addListener(
      'PhoneMicEnrollment',
      (event: { progress: number; level: number }) => {
        if (!acceptProgress.current || phaseRef.current !== 'recording') return;
        const quiet = event.progress >= 0.5;
        setQuietStage(quiet);
        setRemaining(
          Math.max(0, Math.ceil(((quiet ? 1 : 0.5) - event.progress) * 12)),
        );
        levelRef.current = event.level;
        if (event.progress >= 1) {
          setPhase('processing');
          levelRef.current = 0;
        }
      },
    );
    return () => {
      lifecycle.value++;
      acceptProgress.current = false;
      listener.remove();
      void cancelEnrollment().catch(() => {});
    };
  }, [cancelEnrollment, setPhase]);
  const cancel = async (closeAfter = false) => {
    if (phaseRef.current === 'saving') return;
    closing.current ||= closeAfter;
    if (phaseRef.current === 'cancelling') return;
    if (phaseRef.current === 'idle') {
      if (closing.current) close();
      return;
    }
    const current = ++generation.current.value;
    acceptProgress.current = false;
    setPhase('cancelling');
    levelRef.current = 0;
    try {
      await cancelEnrollment();
    } catch {
      if (generation.current.value === current)
        setMessage(c.t('voiceprintFailed'));
    } finally {
      if (generation.current.value === current) {
        setPhase('idle');
        if (closing.current) close();
      }
    }
  };
  const enroll = async () => {
    if (phaseRef.current !== 'idle') return;
    const current = ++generation.current.value;
    closing.current = false;
    acceptProgress.current = true;
    setMessage('');
    setRemaining(6);
    setQuietStage(false);
    levelRef.current = 0;
    setPhase('recording');
    let saving = false;
    try {
      const profile = await c.enrollVoiceprint();
      if (generation.current.value !== current) return;
      acceptProgress.current = false;
      levelRef.current = 0;
      saving = true;
      setPhase('saving');
      await c.saveVoiceprint(profile);
      if (generation.current.value === current)
        setMessage(c.t('voiceprintSaved'));
    } catch (error) {
      if (generation.current.value !== current) return;
      const reason = String((error as Error)?.message || error);
      setMessage(
        c.t(
          reason.includes('INCOMPATIBLE')
            ? 'voiceprintIncompatible'
            : saving
            ? 'storageFailed'
            : reason.includes('TOO_SHORT')
            ? 'voiceprintTooShort'
            : reason.includes('INCONSISTENT')
            ? 'voiceprintInconsistent'
            : 'voiceprintFailed',
        ),
      );
    } finally {
      if (generation.current.value === current) {
        acceptProgress.current = false;
        setPhase('idle');
        levelRef.current = 0;
      }
    }
  };
  const statusMessage =
    message ||
    (c.voiceProfileIncompatible ? c.t('voiceprintIncompatible') : '');
  return (
    <Modal
      visible
      animationType="slide"
      onRequestClose={() => {
        void cancel(true);
      }}
    >
      <SafeAreaView style={{ flex: 1, backgroundColor: p.background }}>
        <View style={s.header}>
          <View
            pointerEvents={phase === 'saving' ? 'none' : 'auto'}
            importantForAccessibility={
              phase === 'saving' ? 'no-hide-descendants' : 'auto'
            }
            style={{ opacity: phase === 'saving' ? 0.4 : 1 }}
          >
            <IconButton
              name="back"
              label={c.t('back')}
              onPress={() => {
                void cancel(true);
              }}
              p={p}
            />
          </View>
          <Text style={{ fontSize: 16, color: p.ink }}>
            {c.t('voiceprint')}
          </Text>
        </View>
        <ScrollView
          contentContainerStyle={[s.content, { flexGrow: 1, gap: 24 }]}
        >
          <Text style={{ fontSize: 23, lineHeight: 34, color: p.ink }}>
            {c.t('voiceprintTitle')}
          </Text>
          <Text style={{ fontSize: 14, lineHeight: 24, color: p.secondary }}>
            {c.t('voiceprintHint')}
          </Text>
          <Text
            style={{
              fontSize: 18,
              lineHeight: 31,
              color: p.ink,
              marginTop: 10,
            }}
          >
            {c.t('voiceprintPhrase')}
          </Text>
          <View style={{ alignItems: 'center', gap: 20, paddingVertical: 16 }}>
            <VoiceWaveform
              p={p}
              recording={recording}
              levelRef={levelRef}
              label={c.t('waveform')}
            />
            {processing ? (
              <ActivityIndicator color={p.secondary} />
            ) : (
              <Text style={{ fontSize: 13, color: p.secondary }}>
                {recording
                  ? `${c.t(
                      quietStage
                        ? 'voiceprintQuietStage'
                        : 'voiceprintNormalStage',
                    )} · ${remaining} ${c.t('seconds')}`
                  : c.voiceProfile
                  ? c.t('voiceprintRegistered')
                  : c.t('voiceprintMissing')}
              </Text>
            )}
          </View>
          {recording || processing ? (
            <ActionButton
              label={c.t(
                phase === 'saving'
                  ? 'voiceprintSaving'
                  : phase === 'cancelling'
                  ? 'voiceprintCancelling'
                  : 'cancel',
              )}
              p={p}
              disabled={locked}
              onPress={() => {
                void cancel();
              }}
            />
          ) : (
            <ActionButton
              label={c.t(
                c.voiceProfile ? 'voiceprintReenroll' : 'voiceprintEnroll',
              )}
              p={p}
              onPress={() => {
                void enroll();
              }}
            />
          )}
          {!!statusMessage && (
            <Text
              accessibilityLiveRegion="polite"
              style={{ fontSize: 13, lineHeight: 22, color: p.secondary }}
            >
              {statusMessage}
            </Text>
          )}
          <View style={[s.between, { gap: 16, paddingVertical: 8 }]}>
            <Text style={{ color: p.ink, fontSize: 14 }}>
              {c.t('voiceFilter')}
            </Text>
            <Switch
              accessibilityLabel={c.t('voiceFilter')}
              value={c.settings.voiceFilter}
              disabled={
                !c.voiceProfile ||
                c.voiceProfileIncompatible ||
                recording ||
                processing
              }
              onValueChange={voiceFilter => c.updateSettings({ voiceFilter })}
              trackColor={{ true: p.accent, false: p.line }}
              thumbColor="#FFFFFF"
            />
          </View>
          <Text style={{ color: p.faint, fontSize: 12, lineHeight: 21 }}>
            {c.t('voiceprintBoundary')}
          </Text>
          {c.voiceProfile && !recording && !processing && (
            <Pressable
              accessibilityRole="button"
              onPress={() => {
                void c
                  .deleteVoiceprint()
                  .then(() => setMessage(''))
                  .catch(() => setMessage(c.t('storageFailed')));
              }}
              style={{ paddingVertical: 16 }}
            >
              <Text style={{ color: p.danger, fontSize: 13 }}>
                {c.t('voiceprintDelete')}
              </Text>
            </Pressable>
          )}
        </ScrollView>
      </SafeAreaView>
    </Modal>
  );
}
