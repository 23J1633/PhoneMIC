import React, {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  ActivityIndicator,
  Animated,
  AppState,
  PanResponder,
  Pressable,
  Text,
  useWindowDimensions,
  View,
  type AppStateStatus,
} from 'react-native';
import Svg, {
  Circle,
  Defs,
  LinearGradient,
  Path,
  Stop,
} from 'react-native-svg';
import type { Palette } from './theme';
import type { CompletionAction, InputMode } from './types';
import { endActionAt, endTargets, type EndGestureAction } from './endGesture';

const paths = {
  mic: 'M12 15a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3ZM5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8',
  settings:
    'm9.4 3-.6 2.1-2 .9-2-.5-2.1 3.6L4.3 11v2l-1.6 1.9 2.1 3.6 2-.5 2 .9.6 2.1h4.2l.6-2.1 2-.9 2 .5 2.1-3.6-1.6-1.9v-2l1.6-1.9-2.1-3.6-2 .5-2-.9-.6-2.1Z M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0',
  back: 'm14 5-7 7 7 7',
  arrow: 'm9 5 7 7-7 7',
  more: 'M12 5h.01M12 12h.01M12 19h.01',
  refresh: 'M20 8a8 8 0 1 0 0 8M20 3v5h-5',
  copy: 'M9 8h10v13H9zM15 8V3H4v13h5',
  send: 'm21 3-7 18-4-7-7-4 18-7ZM10 14 21 3',
  guide: 'M5 7h9a5 5 0 0 1 0 10H5m0 0 5-5m-5 5 5 5',
  trash: 'M4 7h16M9 7V4h6v3M6 7l1 14h10l1-14M10 11v6M14 11v6',
} as const;
export type IconName = keyof typeof paths;
export function Icon({
  name,
  color,
  size = 21,
}: {
  name: IconName;
  color: string;
  size?: number;
}) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path
        d={paths[name]}
        stroke={color}
        strokeWidth={1.6}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </Svg>
  );
}
export function IconButton({
  name,
  label,
  onPress,
  p,
}: {
  name: IconName;
  label: string;
  onPress: () => void;
  p: Palette;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      hitSlop={6}
      style={({ pressed }) => ({
        width: 44,
        height: 44,
        alignItems: 'center',
        justifyContent: 'center',
        opacity: pressed ? 0.45 : 1,
      })}
    >
      <Icon name={name} color={p.secondary} />
    </Pressable>
  );
}
export function ActionButton({
  label,
  onPress,
  p,
  disabled = false,
}: {
  label: string;
  onPress: () => void;
  p: Palette;
  disabled?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      disabled={disabled}
      style={({ pressed }) => ({
        height: 46,
        borderRadius: 12,
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: p.accent,
        opacity: disabled ? 0.35 : pressed ? 0.65 : 1,
      })}
    >
      <Text style={{ color: '#FFFFFF', fontSize: 14, fontWeight: '500' }}>
        {label}
      </Text>
    </Pressable>
  );
}
export function Options<T extends string>({
  p,
  value,
  options,
  onChange,
}: {
  p: Palette;
  value: T;
  options: { value: T; label: string }[];
  onChange: (value: T) => void;
}) {
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 2 }}>
      {options.map(option => (
        <Pressable
          key={option.value}
          accessibilityRole="radio"
          accessibilityState={{ checked: value === option.value }}
          onPress={() => onChange(option.value)}
          style={{
            paddingHorizontal: 9,
            minHeight: 44,
            justifyContent: 'center',
          }}
        >
          <Text
            style={{
              fontSize: 13,
              color: value === option.value ? p.accent : p.faint,
              fontWeight: value === option.value ? '600' : '400',
            }}
          >
            {option.label}
          </Text>
        </Pressable>
      ))}
    </View>
  );
}
export const VoiceWaveform = React.memo(function VoiceWaveform({
  p,
  recording,
  levelRef,
  label,
  visible = true,
}: {
  p: Palette;
  recording: boolean;
  levelRef: { readonly current: number };
  label: string;
  visible?: boolean;
}) {
  const { width } = useWindowDimensions();
  const [samples] = useState(() => Array<number>(43).fill(0.1));
  const [bars] = useState(() =>
    samples.map(value => new Animated.Value(value)),
  );
  useLayoutEffect(() => {
    let timer: ReturnType<typeof setInterval> | undefined;
    let running = false;
    const targets = Array<number>(bars.length).fill(0.1);
    const animations: (Animated.CompositeAnimation | undefined)[] = [];
    const sample = () => {
      if (!running) return;
      samples.copyWithin(0, 1);
      samples[samples.length - 1] = Math.max(0.1, levelRef.current);
      bars.forEach((bar, i) => {
        if (Math.abs(targets[i] - samples[i]) < 0.005) return;
        targets[i] = samples[i];
        animations[i]?.stop();
        animations[i] = Animated.timing(bar, {
          toValue: samples[i],
          duration: 110,
          useNativeDriver: true,
          isInteraction: false,
        });
        animations[i]?.start();
      });
    };
    const pause = () => {
      running = false;
      clearInterval(timer);
      timer = undefined;
      animations.forEach(animation => animation?.stop());
      samples.fill(0.1);
      targets.fill(0.1);
      bars.forEach(bar => {
        bar.stopAnimation();
        bar.setValue(0.1);
      });
    };
    const updateVisibility = (appState: AppStateStatus | null) => {
      pause();
      if (
        !recording ||
        !visible ||
        appState === 'background' ||
        appState === 'inactive'
      )
        return;
      running = true;
      sample();
      timer = setInterval(sample, 100);
    };
    updateVisibility(AppState.currentState);
    const subscription = AppState.addEventListener('change', updateVisibility);
    return () => {
      pause();
      subscription.remove();
    };
  }, [recording, visible, bars, samples, levelRef]);
  return (
    <View
      accessibilityLabel={label}
      style={{
        width: Math.min(280, width - 84),
        height: 32,
        opacity: recording && visible ? 1 : 0,
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
      }}
    >
      {bars.map((bar, i) => (
        <Animated.View
          key={i}
          style={{
            width: 2.5,
            height: 30,
            borderRadius: 1.25,
            backgroundColor: recording ? p.secondary : p.line,
            transform: [{ scaleY: recording && visible ? bar : 0.1 }],
          }}
        />
      ))}
    </View>
  );
});
export const MicButton = React.memo(function MicButton({
  p,
  recording,
  disabled,
  loading,
  label,
  pressIn,
  pressOut,
  press,
  mode,
  guideLabel,
  sendLabel,
  clearLabel,
  finishWithAction,
  discardRecording,
  haptic,
}: {
  p: Palette;
  recording: boolean;
  disabled: boolean;
  loading: boolean;
  label: string;
  pressIn: () => void;
  pressOut: () => void;
  press: () => void;
  mode: InputMode;
  guideLabel: string;
  sendLabel: string;
  clearLabel: string;
  finishWithAction: (action: CompletionAction) => void;
  discardRecording: () => void;
  haptic: () => void;
}) {
  const [pressed, setPressed] = useState(false);
  const [finishing, setFinishing] = useState(false);
  const [scale] = useState(() => new Animated.Value(1));
  const [ring] = useState(() => new Animated.Value(0));
  const previousRecording = useRef(recording);
  const [menuVisible, setMenuVisible] = useState(false);
  const [selected, setSelected] = useState<EndGestureAction | null>(null);
  const touchSurface = useRef<View>(null);
  const origin = useRef({ x: 0, y: 0 });
  const revealTimer = useRef<ReturnType<typeof setTimeout> | undefined>(
    undefined,
  );
  const gesture = useRef({
    menu: false,
    moved: false,
    action: null as EndGestureAction | null,
  });
  const [guidePoint, setGuidePoint] = useState({ x: 48, y: -24 });
  const [reveal] = useState(() => new Animated.Value(0));
  const latest = useRef({
    disabled,
    recording,
    mode,
    pressIn,
    pressOut,
    press,
    finishWithAction,
    discardRecording,
    haptic,
  });
  latest.current = {
    disabled,
    recording,
    mode,
    pressIn,
    pressOut,
    press,
    finishWithAction,
    discardRecording,
    haptic,
  };
  useEffect(() => {
    const stopped = previousRecording.current && !recording;
    previousRecording.current = recording;
    if (!recording && !stopped) return;
    setFinishing(stopped);
    const timer = stopped
      ? setTimeout(() => setFinishing(false), 260)
      : undefined;
    ring.setValue(0);
    const feedback = Animated.sequence([
      Animated.timing(ring, {
        toValue: 1,
        duration: 180,
        useNativeDriver: true,
      }),
      Animated.timing(ring, {
        toValue: 0,
        duration: 620,
        useNativeDriver: true,
      }),
    ]);
    feedback.start();
    return () => {
      clearTimeout(timer);
      feedback.stop();
    };
  }, [recording, ring]);
  const animate = useCallback(
    (value: number) =>
      Animated.spring(scale, {
        toValue: value,
        speed: 40,
        bounciness: 2,
        useNativeDriver: true,
      }).start(),
    [scale],
  );
  const resetGesture = useCallback(() => {
    clearTimeout(revealTimer.current);
    setPressed(false);
    setMenuVisible(false);
    setSelected(null);
    animate(1);
    setGuidePoint({ x: 48, y: -24 });
  }, [animate]);
  const pan = useMemo(
    () =>
      PanResponder.create({
        onStartShouldSetPanResponder: () => !latest.current.disabled,
        onMoveShouldSetPanResponder: () => !latest.current.disabled,
        onPanResponderGrant: event => {
          gesture.current = { menu: false, moved: false, action: null };
          origin.current = {
            x: event.nativeEvent.pageX - 48,
            y: event.nativeEvent.pageY - 48,
          };
          touchSurface.current?.measureInWindow((x, y) => {
            origin.current = { x, y };
          });
          setPressed(true);
          latest.current.haptic();
          animate(0.97);
          latest.current.pressIn();
          if (latest.current.recording && latest.current.mode === 'toggle') {
            revealTimer.current = setTimeout(() => {
              if (!latest.current.recording || latest.current.disabled) return;
              gesture.current.menu = true;
              setMenuVisible(true);
              latest.current.haptic();
              reveal.setValue(0);
              Animated.spring(reveal, {
                toValue: 1,
                useNativeDriver: true,
                speed: 24,
                bounciness: 4,
              }).start();
            }, 400);
          }
        },
        onPanResponderMove: (event, movement) => {
          if (!gesture.current.menu) {
            if (Math.hypot(movement.dx, movement.dy) > 14) {
              clearTimeout(revealTimer.current);
              gesture.current.moved = true;
            }
            return;
          }
          const x = event.nativeEvent.pageX - origin.current.x;
          const y = event.nativeEvent.pageY - origin.current.y;
          const action = endActionAt(x, y);
          if (action !== gesture.current.action) {
            gesture.current.action = action;
            setSelected(action);
            if (action) latest.current.haptic();
          }
          setGuidePoint({
            x: Math.max(-65, Math.min(161, x)),
            y: Math.max(-115, Math.min(0, y)),
          });
        },
        onPanResponderRelease: () => {
          const { menu, action, moved } = gesture.current;
          resetGesture();
          latest.current.pressOut();
          if (menu) {
            if (action === 'clear') latest.current.discardRecording();
            else if (action) latest.current.finishWithAction(action);
          } else if (!moved) latest.current.press();
        },
        onPanResponderTerminationRequest: () => false,
        onPanResponderTerminate: () => {
          resetGesture();
          latest.current.pressOut();
        },
      }),
    [animate, resetGesture, reveal],
  );
  useEffect(() => () => clearTimeout(revealTimer.current), []);
  return (
    <View
      ref={touchSurface}
      collapsable={false}
      style={{ width: 96, height: 96, transform: [{ translateY: 10 }] }}
    >
      {menuVisible && (
        <Animated.View
          pointerEvents="none"
          style={{
            position: 'absolute',
            left: -90,
            top: -150,
            opacity: reveal,
          }}
        >
          <Svg width={276} height={246} viewBox="-90 -150 276 246">
            <Path
              d={`M48 5 C48 -16 ${guidePoint.x} ${guidePoint.y + 20} ${
                guidePoint.x
              } ${guidePoint.y}`}
              stroke={selected ? p.accent : p.faint}
              strokeWidth={1.3}
              strokeDasharray="3 5"
              strokeLinecap="round"
              fill="none"
            />
            <Circle
              cx={guidePoint.x}
              cy={guidePoint.y}
              r={3}
              fill={selected ? p.accent : p.faint}
            />
          </Svg>
        </Animated.View>
      )}
      {menuVisible &&
        endTargets.map(target => (
          <Animated.View
            key={target.action}
            pointerEvents="none"
            style={{
              position: 'absolute',
              left: target.x - 32,
              top: target.y - 32,
              width: 64,
              height: 64,
              alignItems: 'center',
              justifyContent: 'center',
              gap: 5,
              borderRadius: 32,
              backgroundColor:
                selected === target.action ? p.accentSoft : p.surface,
              borderWidth: 1,
              borderColor: selected === target.action ? p.accent : p.line,
              boxShadow: p.dark
                ? '0px 4px 16px rgba(0,0,0,0.3)'
                : '0px 4px 16px rgba(0,0,0,0.06)',
              opacity: reveal,
              transform: [
                {
                  scale: reveal.interpolate({
                    inputRange: [0, 1],
                    outputRange: [0.85, 1],
                  }),
                },
              ],
            }}
          >
            <Icon
              name={target.action === 'clear' ? 'trash' : target.action}
              size={19}
              color={selected === target.action ? p.accent : p.secondary}
            />
            <Text
              style={{
                fontSize: 11,
                color: selected === target.action ? p.accent : p.secondary,
              }}
            >
              {target.action === 'send'
                ? sendLabel
                : target.action === 'clear'
                ? clearLabel
                : guideLabel}
            </Text>
          </Animated.View>
        ))}
      <Animated.View style={{ transform: [{ scale }] }}>
        <View
          {...pan.panHandlers}
          onTouchStart={() => {
            if (disabled) haptic();
          }}
          testID="voice-button"
          accessible
          accessibilityRole="button"
          accessibilityLabel={label}
          accessibilityState={{ disabled, selected: recording }}
          accessibilityActions={[
            { name: 'activate' },
            ...(recording && mode === 'toggle'
              ? [
                  { name: 'guide', label: guideLabel },
                  { name: 'send', label: sendLabel },
                  { name: 'clear', label: clearLabel },
                ]
              : []),
          ]}
          onAccessibilityAction={event => {
            if (disabled) return;
            const action = event.nativeEvent.actionName;
            if (recording && action === 'clear') discardRecording();
            else if (recording && (action === 'send' || action === 'guide'))
              finishWithAction(action);
            else if (mode === 'hold') {
              if (recording) pressOut();
              else pressIn();
            } else press();
          }}
          style={{
            width: 96,
            height: 96,
            borderRadius: 48,
            backgroundColor: p.surface,
            boxShadow: p.dark
              ? '0px 6px 18px rgba(0,0,0,0.35)'
              : pressed
              ? '0px 2px 6px rgba(0,0,0,0.06)'
              : '0px 6px 18px rgba(0,0,0,0.08)',
            alignItems: 'center',
            justifyContent: 'center',
            opacity: disabled ? 0.4 : 1,
          }}
        >
          <View
            pointerEvents="none"
            style={{ position: 'absolute', left: 0, top: 0 }}
          >
            <Svg width={96} height={96} viewBox="0 0 96 96">
              <Defs>
                <LinearGradient id="buttonSurface" x1="0" y1="0" x2="0" y2="1">
                  <Stop
                    offset="0"
                    stopColor={
                      p.dark
                        ? pressed
                          ? '#191919'
                          : '#303030'
                        : pressed
                        ? '#E9E9E9'
                        : '#FFFFFF'
                    }
                  />
                  <Stop
                    offset="1"
                    stopColor={
                      p.dark ? '#171717' : pressed ? '#FAFAFA' : '#ECECEC'
                    }
                  />
                </LinearGradient>
              </Defs>
              <Circle
                cx="48"
                cy="48"
                r="47.3"
                fill="url(#buttonSurface)"
                stroke={p.dark ? '#3A3A3A' : '#E3E3E3'}
                strokeWidth="0.8"
              />
              <Path
                d="M10 43a38 38 0 0 1 76 0"
                fill="none"
                stroke={p.dark ? '#414141' : '#FFFFFF'}
                strokeWidth="0.8"
                opacity="0.8"
              />
            </Svg>
          </View>
          <Animated.View
            pointerEvents="none"
            style={{
              position: 'absolute',
              left: -1,
              top: -1,
              width: 98,
              height: 98,
              borderRadius: 49,
              borderWidth: 1.5,
              borderColor: '#FFFFFF',
              boxShadow: p.dark
                ? '0px 0px 12px rgba(255,255,255,0.15)'
                : '0px 0px 8px rgba(255,255,255,0.9)',
              opacity: ring,
              transform: [
                {
                  scale: ring.interpolate({
                    inputRange: [0, 1],
                    outputRange: [1, 1.045],
                  }),
                },
              ],
            }}
          />
          {recording || finishing ? (
            <View
              style={{
                width: 18,
                height: 18,
                borderRadius: 3,
                backgroundColor: p.dark ? '#F17E79' : '#D96761',
              }}
            />
          ) : loading ? (
            <ActivityIndicator size={22} color={p.secondary} />
          ) : (
            <Icon name="mic" size={30} color={p.ink} />
          )}
        </View>
      </Animated.View>
    </View>
  );
});
