import React from 'react';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';
import { PanResponder, Text } from 'react-native';
import { MicButton } from '../src/components';
import { usePalette } from '../src/theme';

describe('microphone clear gestures', () => {
  let root: ReactTestRenderer;
  let gesture: Parameters<typeof PanResponder.create>[0];
  let point: { x: number; y: number };
  const callbacks = {
    pressIn: jest.fn(),
    pressOut: jest.fn(),
    press: jest.fn(),
    prepareInputClear: jest.fn(() => true),
    clearInput: jest.fn(),
    cancelInputClear: jest.fn(),
    finishWithAction: jest.fn(),
    discardRecording: jest.fn(),
    haptic: jest.fn(),
  };
  function Harness({
    recording = false,
    mode = 'toggle' as 'toggle' | 'hold',
  }) {
    return (
      <MicButton
        p={usePalette('light')}
        recording={recording}
        mode={mode}
        disabled={false}
        loading={false}
        label="Microphone"
        guideLabel="Guide"
        sendLabel="Send"
        clearLabel="Discard recording"
        clearInputLabel="Clear text field"
        {...callbacks}
      />
    );
  }
  const event = (x = 148, y = 248) =>
    ({ nativeEvent: { pageX: x, pageY: y } } as never);
  const hold = async () => {
    await act(async () => {
      gesture.onPanResponderGrant!(event(), {} as never);
    });
    await act(async () => {
      jest.advanceTimersByTime(400);
    });
  };
  const move = async (x: number, y: number) =>
    act(async () => {
      point = { x, y };
      gesture.onPanResponderMove!(event(x, y), {
        dx: x - 148,
        dy: y - 248,
      } as never);
    });
  const release = async () =>
    act(async () => {
      gesture.onPanResponderRelease!(event(point.x, point.y), {} as never);
    });
  beforeEach(async () => {
    jest.useFakeTimers();
    jest.clearAllMocks();
    point = { x: 148, y: 248 };
    callbacks.prepareInputClear.mockReturnValue(true);
    jest.spyOn(PanResponder, 'create').mockImplementation(config => {
      gesture = config;
      return { panHandlers: {} };
    });
    await act(async () => {
      root = create(<Harness />, {
        createNodeMock: () => ({
          measureInWindow: (cb: (x: number, y: number) => void) => cb(100, 200),
        }),
      });
    });
  });
  afterEach(async () => {
    await act(async () => root.unmount());
    jest.restoreAllMocks();
    jest.useRealTimers();
  });

  it('offers only clear while idle and waits for an upward release', async () => {
    await hold();
    expect(callbacks.prepareInputClear).toHaveBeenCalledTimes(1);
    const labels = root.root
      .findAllByType(Text)
      .map(node => node.props.children);
    expect(labels).toContain('Clear text field');
    expect(labels).not.toContain('Guide');
    expect(labels).not.toContain('Send');
    await move(148, 86);
    expect(callbacks.clearInput).not.toHaveBeenCalled();
    await release();
    expect(callbacks.clearInput).toHaveBeenCalledTimes(1);
    expect(callbacks.press).not.toHaveBeenCalled();
    expect(callbacks.discardRecording).not.toHaveBeenCalled();
  });
  it('cancels when the finger returns to the microphone before release', async () => {
    await hold();
    await move(148, 86);
    await move(148, 248);
    await release();
    expect(callbacks.cancelInputClear).toHaveBeenCalledTimes(1);
    expect(callbacks.clearInput).not.toHaveBeenCalled();
    expect(callbacks.press).not.toHaveBeenCalled();
  });
  it('does not clear or record when the drag starts before the long press', async () => {
    await act(async () => {
      gesture.onPanResponderGrant!(event(), {} as never);
    });
    await move(148, 86);
    await act(async () => {
      jest.advanceTimersByTime(500);
    });
    await release();
    expect(callbacks.prepareInputClear).not.toHaveBeenCalled();
    expect(callbacks.clearInput).not.toHaveBeenCalled();
    expect(callbacks.press).not.toHaveBeenCalled();
  });
  it('uses the release position when the final return move was coalesced', async () => {
    await hold();
    await move(148, 86);
    point = { x: 148, y: 248 };
    await release();
    expect(callbacks.clearInput).not.toHaveBeenCalled();
    expect(callbacks.cancelInputClear).toHaveBeenCalledTimes(1);
  });
  it('cancels an interrupted gesture without executing clear', async () => {
    await hold();
    await move(148, 86);
    await act(async () => {
      gesture.onPanResponderTerminate!(event(), {} as never);
    });
    expect(callbacks.cancelInputClear).toHaveBeenCalledTimes(1);
    expect(callbacks.clearInput).not.toHaveBeenCalled();
  });
  it('still discards the recording instead of clearing the computer while recording', async () => {
    await act(async () => root.update(<Harness recording />));
    await hold();
    await move(148, 86);
    await release();
    expect(callbacks.discardRecording).toHaveBeenCalledTimes(1);
    expect(callbacks.prepareInputClear).not.toHaveBeenCalled();
    expect(callbacks.clearInput).not.toHaveBeenCalled();
  });
  it('preserves normal tapping and immediate hold-to-talk behavior', async () => {
    await act(async () => {
      gesture.onPanResponderGrant!(event(), {} as never);
    });
    await release();
    expect(callbacks.press).toHaveBeenCalledTimes(1);
    await act(async () => root.update(<Harness mode="hold" />));
    await act(async () => {
      gesture.onPanResponderGrant!(event(), {} as never);
    });
    expect(callbacks.pressIn).toHaveBeenCalledTimes(2);
    await act(async () => {
      jest.advanceTimersByTime(500);
    });
    await release();
    expect(callbacks.prepareInputClear).not.toHaveBeenCalled();
  });
  it('does not turn a refused accessibility clear into a recording tap', async () => {
    callbacks.prepareInputClear.mockReturnValue(false);
    await act(async () => {
      root.root
        .findAllByProps({ testID: 'voice-button' })[0]
        .props.onAccessibilityAction({
          nativeEvent: { actionName: 'clearInput' },
        });
    });
    expect(callbacks.clearInput).not.toHaveBeenCalled();
    expect(callbacks.press).not.toHaveBeenCalled();
  });
});
