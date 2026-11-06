import { DictationSession } from '../src/session';
import type { Packet } from '../src/types';

describe('dictation delivery', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());
  const setup = (stream: boolean) => {
    const send = jest.fn<Promise<void>, [Packet]>().mockResolvedValue();
    const done = jest.fn();
    const failed = jest.fn();
    const session = new DictationSession(
      'session-test',
      stream,
      send,
      done,
      failed,
    );
    const ack = (seq: number) =>
      session.receive({ type: 'ack', session: 'session-test', seq });
    return { session, send, done, failed, ack };
  };
  it('waits for captured focus before clearing, and completes only after its acknowledgement', () => {
    const { session, send, ack, done } = setup(false);
    session.begin();
    session.clearInput();
    expect(send).toHaveBeenCalledTimes(1);
    ack(1);
    expect(send.mock.calls[1][0]).toEqual({
      type: 'clear',
      session: 'session-test',
      seq: 2,
      final: true,
    });
    expect(done).not.toHaveBeenCalled();
    ack(2);
    expect(done).toHaveBeenCalledTimes(1);
    session.clearInput();
    expect(send).toHaveBeenCalledTimes(2);
  });
  it('never clears after a cancelled gesture or a late focus acknowledgement', () => {
    const { session, send, ack } = setup(false);
    session.begin();
    session.clearInput();
    session.cancel();
    ack(1);
    expect(send.mock.calls.map(([packet]) => packet.type)).toEqual([
      'begin',
      'cancel',
    ]);
  });
  it('does not repeat a clear whose acknowledgement was lost', () => {
    const { session, send, ack, failed } = setup(false);
    session.begin();
    ack(1);
    session.clearInput();
    jest.advanceTimersByTime(6000);
    ack(2);
    session.clearInput();
    expect(failed).toHaveBeenCalledTimes(1);
    expect(
      send.mock.calls.filter(([packet]) => packet.type === 'clear'),
    ).toHaveLength(1);
  });
  it('coalesces partials and waits for an acknowledgement before sending more', () => {
    const { session, send, ack, done } = setup(true);
    session.begin();
    session.update('你好', false);
    session.update('你好世界', false);
    expect(send).toHaveBeenCalledTimes(1);
    ack(1);
    expect(send.mock.calls[1][0].text).toBe('你好世界');
    session.update('你好世界。', true);
    expect(send).toHaveBeenCalledTimes(2);
    ack(2);
    expect(send.mock.calls[2][0]).toMatchObject({
      text: '你好世界。',
      final: true,
      seq: 3,
    });
    expect(done).not.toHaveBeenCalled();
    ack(3);
    expect(done).toHaveBeenCalledTimes(1);
    ack(3);
    jest.advanceTimersByTime(10000);
    expect(done).toHaveBeenCalledTimes(1);
  });
  it('keeps tap-mode transcripts local until the final result', () => {
    const { session, send, ack, done } = setup(false);
    session.begin();
    ack(1);
    session.update('hello', false);
    session.update('hello world', false);
    expect(send).toHaveBeenCalledTimes(1);
    session.update('hello world.', true);
    expect(send.mock.calls[1][0].final).toBe(true);
    ack(2);
    expect(done).toHaveBeenCalledTimes(1);
  });
  it('ignores late acknowledgements from a different recording', () => {
    const { session, send, ack } = setup(true);
    session.begin();
    session.update('hello', true);
    session.receive({ type: 'ack', session: 'old-session', seq: 1 });
    expect(send).toHaveBeenCalledTimes(1);
    ack(1);
    expect(send).toHaveBeenCalledTimes(2);
    session.dispose();
  });
  it('reports an unconfirmed send once and never replays it automatically', () => {
    const { session, send, failed } = setup(true);
    session.begin();
    session.update('uncertain', true);
    jest.advanceTimersByTime(7000);
    expect(failed).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledTimes(2);
    expect(send.mock.calls[1][0].type).toBe('cancel');
    session.begin();
    session.update('retry', true);
    expect(send).toHaveBeenCalledTimes(2);
    expect(send.mock.calls.some(([packet]) => packet.type === 'text')).toBe(
      false,
    );
  });
  it('stops on receiver input errors', () => {
    const { session, ack, send, failed } = setup(true);
    session.begin();
    ack(1);
    session.update('test', false);
    session.receive({
      type: 'error',
      session: 'session-test',
      seq: 2,
      message: 'Focus changed',
    });
    expect(failed).toHaveBeenCalledWith('Focus changed', undefined);
    session.update('test more', true);
    expect(send).toHaveBeenCalledTimes(3);
    expect(send.mock.calls[2][0].type).toBe('cancel');
  });
  it('allows a longer acknowledgement window for Bluetooth text bursts', () => {
    const send = jest.fn<Promise<void>, [Packet]>().mockResolvedValue();
    const done = jest.fn();
    const failed = jest.fn();
    const session = new DictationSession(
      'session-test',
      false,
      send,
      done,
      failed,
      packet => (packet.type === 'text' ? 20000 : 6000),
    );
    session.begin();
    session.receive({ type: 'ack', session: 'session-test', seq: 1 });
    session.update('a longer Bluetooth dictation', true);
    jest.advanceTimersByTime(10000);
    expect(failed).not.toHaveBeenCalled();
    session.receive({ type: 'ack', session: 'session-test', seq: 2 });
    expect(done).toHaveBeenCalledTimes(1);
    jest.advanceTimersByTime(20000);
    expect(failed).not.toHaveBeenCalled();
  });
  it('attaches the completion shortcut only to final text and never replays it after timeout', () => {
    const send = jest.fn<Promise<void>, [Packet]>().mockResolvedValue();
    const session = new DictationSession(
      'shortcut-test',
      true,
      send,
      jest.fn(),
      jest.fn(),
      6000,
      { key: 'PageDown', modifiers: ['Control'] },
    );
    session.begin();
    session.receive({ type: 'ack', session: 'shortcut-test', seq: 1 });
    session.update('draft', false);
    expect(send.mock.calls[1][0].shortcut).toBeUndefined();
    session.receive({ type: 'ack', session: 'shortcut-test', seq: 2 });
    session.update('final text', true);
    expect(send.mock.calls[2][0].shortcut).toEqual({
      key: 'PageDown',
      modifiers: ['Control'],
    });
    jest.advanceTimersByTime(10000);
    expect(send.mock.calls.filter(([packet]) => packet.shortcut)).toHaveLength(
      1,
    );
    expect(send.mock.calls.at(-1)?.[0].type).toBe('cancel');
  });
  it('does not trigger a completion key for an empty recording', () => {
    const send = jest.fn<Promise<void>, [Packet]>().mockResolvedValue();
    const session = new DictationSession(
      'shortcut-test',
      false,
      send,
      jest.fn(),
      jest.fn(),
      6000,
      { key: 'Enter', modifiers: [] },
    );
    session.begin();
    session.receive({ type: 'ack', session: 'shortcut-test', seq: 1 });
    session.update('', true);
    expect(
      send.mock.calls.every(([packet]) => packet.shortcut === undefined),
    ).toBe(true);
    session.dispose();
  });
  it('selects a one-shot guide shortcut when stopping an active recording', () => {
    const send = jest.fn<Promise<void>, [Packet]>().mockResolvedValue();
    const session = new DictationSession(
      'gesture-test',
      false,
      send,
      jest.fn(),
      jest.fn(),
    );
    session.begin();
    session.receive({ type: 'ack', session: 'gesture-test', seq: 1 });
    session.update('draft', false);
    session.setShortcut({ key: 'PageUp', modifiers: ['Control'] });
    session.update('recognized final', true);
    session.setShortcut({ key: 'Enter', modifiers: [] });
    expect(send.mock.calls[1][0]).toMatchObject({
      final: true,
      shortcut: { key: 'PageUp', modifiers: ['Control'] },
    });
    session.dispose();
  });
  it('can explicitly clear a configured shortcut for plain typing', () => {
    const send = jest.fn<Promise<void>, [Packet]>().mockResolvedValue();
    const session = new DictationSession(
      'plain-test',
      false,
      send,
      jest.fn(),
      jest.fn(),
      6000,
      { key: 'Enter', modifiers: [] },
    );
    session.begin();
    session.receive({ type: 'ack', session: 'plain-test', seq: 1 });
    session.setShortcut(undefined);
    session.update('type without sending', true);
    expect(send.mock.calls[1][0].shortcut).toBeUndefined();
    session.dispose();
  });
});
