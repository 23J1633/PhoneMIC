import React from 'react';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';
import { Animated, AppState, type AppStateStatus } from 'react-native';
import { VoiceWaveform } from '../src/components';
import { usePalette } from '../src/theme';

describe('waveform recording lifecycle', () => {
  let root: ReactTestRenderer;
  let onAppState: (state: AppStateStatus) => void;
  let levelRef: { current: number };
  function Harness({ recording = true, visible = true }) {
    return (
      <VoiceWaveform
        p={usePalette('light')}
        recording={recording}
        visible={visible}
        levelRef={levelRef}
        label="Waveform"
      />
    );
  }
  const animatedBars = () =>
    root.root
      .findAllByType(Animated.View)
      .map(
        bar => bar.props.style.transform[0].scaleY as number | Animated.Value,
      );
  const read = (bar: number | Animated.Value) =>
    typeof bar === 'number'
      ? bar
      : (bar as unknown as { __getValue: () => number }).__getValue();
  const opacity = () =>
    root.root.findAllByProps({ accessibilityLabel: 'Waveform' })[0].props.style
      .opacity;

  beforeEach(async () => {
    jest.useFakeTimers();
    levelRef = { current: 0.9 };
    jest
      .spyOn(AppState, 'addEventListener')
      .mockImplementation((_, listener) => {
        onAppState = listener;
        return { remove: jest.fn() };
      });
    // Leave animations in flight so stopping must cancel them, not just the sample interval.
    jest.spyOn(Animated, 'timing').mockImplementation((value, config) => {
      let timer: ReturnType<typeof setTimeout> | undefined;
      return {
        start: () => {
          timer = setTimeout(
            () => (value as Animated.Value).setValue(config.toValue as number),
            110,
          );
        },
        stop: () => clearTimeout(timer),
        reset: () => clearTimeout(timer),
      };
    });
    await act(async () => {
      root = create(<Harness />);
    });
  });
  afterEach(async () => {
    await act(async () => root.unmount());
    jest.useRealTimers();
    jest.restoreAllMocks();
  });

  it('immediately resets stopped bars and never replays pending audio animations', async () => {
    expect(opacity()).toBe(1);
    await act(async () => {
      jest.advanceTimersByTime(350);
    });
    const previousBars = animatedBars();
    expect(previousBars.some(bar => read(bar) > 0.1)).toBe(true);
    await act(async () => {
      root.update(<Harness recording={false} />);
    });
    expect(opacity()).toBe(0);
    expect(animatedBars()).toHaveLength(43);
    expect(animatedBars().every(bar => bar === 0.1)).toBe(true);
    expect(previousBars.every(bar => read(bar) === 0.1)).toBe(true);
    const animations = jest.mocked(Animated.timing).mock.calls.length;
    levelRef.current = 1;
    await act(async () => {
      jest.advanceTimersByTime(5000);
    });
    expect(Animated.timing).toHaveBeenCalledTimes(animations);
    expect(previousBars.every(bar => read(bar) === 0.1)).toBe(true);
  });

  it('clears hidden history before returning from settings to a live waveform', async () => {
    await act(async () => {
      jest.advanceTimersByTime(350);
    });
    const previousBars = animatedBars();
    await act(async () => {
      root.update(<Harness visible={false} />);
    });
    expect(opacity()).toBe(0);
    expect(animatedBars().every(bar => bar === 0.1)).toBe(true);
    expect(previousBars.every(bar => read(bar) === 0.1)).toBe(true);
    const animations = jest.mocked(Animated.timing).mock.calls.length;
    await act(async () => {
      jest.advanceTimersByTime(1000);
    });
    expect(Animated.timing).toHaveBeenCalledTimes(animations);
    levelRef.current = 0.4;
    await act(async () => {
      root.update(<Harness />);
    });
    expect(opacity()).toBe(1);
    await act(async () => {
      jest.advanceTimersByTime(110);
    });
    expect(
      animatedBars()
        .map(read)
        .filter(value => value > 0.1),
    ).toEqual([0.4]);
  });

  it('cancels in-flight animation on background and resumes only fresh levels', async () => {
    await act(async () => {
      jest.advanceTimersByTime(350);
    });
    const previousBars = animatedBars();
    await act(async () => {
      onAppState('background');
    });
    expect(previousBars.every(bar => read(bar) === 0.1)).toBe(true);
    const animations = jest.mocked(Animated.timing).mock.calls.length;
    await act(async () => {
      jest.advanceTimersByTime(1000);
    });
    expect(Animated.timing).toHaveBeenCalledTimes(animations);
    levelRef.current = 0.2;
    await act(async () => {
      onAppState('active');
      jest.advanceTimersByTime(110);
    });
    expect(
      animatedBars()
        .map(read)
        .filter(value => value > 0.1),
    ).toEqual([0.2]);
  });
});
