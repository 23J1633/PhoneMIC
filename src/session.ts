import type { KeyboardShortcut, Packet, Reply } from './types';

// Only one packet is in flight. New partials replace the queued snapshot.
export class DictationSession {
  private sequence = 0;
  private inFlight: Packet | null = null;
  private desiredText = '';
  private acceptedText = '';
  private ending = false;
  private begun = false;
  private dead = false;
  private completed = false;
  private timer: ReturnType<typeof setTimeout> | undefined;
  constructor(
    readonly id: string,
    private readonly stream: boolean,
    private readonly send: (packet: Packet) => Promise<unknown>,
    private readonly done: () => void,
    private readonly failed: (message: string) => void,
    private readonly timeoutMs: number | ((packet: Packet) => number) = 6000,
    private shortcut?: KeyboardShortcut,
  ) {}
  setShortcut(shortcut?: KeyboardShortcut) {
    if (!this.ending && !this.dead && !this.completed) this.shortcut = shortcut;
  }
  begin() {
    if (!this.begun && !this.dead) {
      this.begun = true;
      this.dispatch({ type: 'begin', session: this.id, seq: ++this.sequence });
    }
  }
  update(text: string, final: boolean) {
    if (this.dead || this.completed) {
      return;
    }
    this.desiredText = text;
    this.ending ||= final;
    this.pump();
  }
  receive(reply: Reply) {
    if (
      this.dead ||
      this.completed ||
      !this.inFlight ||
      reply.session !== this.id ||
      reply.seq !== this.inFlight.seq
    ) {
      return;
    }
    if (reply.type === 'error') {
      this.abort(reply.message || reply.code || 'Input failed');
      return;
    }
    if (reply.type !== 'ack') {
      return;
    }
    const packet = this.inFlight;
    clearTimeout(this.timer);
    this.inFlight = null;
    if (packet.type === 'text') {
      this.acceptedText = packet.text || '';
    }
    if (packet.final) {
      this.completed = true;
      this.done();
      return;
    }
    this.pump();
  }
  private pump() {
    if (!this.begun || this.dead || this.inFlight || this.completed) {
      return;
    }
    if (
      !this.ending &&
      (!this.stream || this.desiredText === this.acceptedText)
    ) {
      return;
    }
    this.dispatch({
      type: 'text',
      session: this.id,
      seq: ++this.sequence,
      text: this.desiredText,
      final: this.ending,
      ...(this.ending && this.desiredText.trim() && this.shortcut
        ? { shortcut: this.shortcut }
        : {}),
    });
  }
  private dispatch(packet: Packet) {
    this.inFlight = packet;
    const timeout =
      typeof this.timeoutMs === 'function'
        ? this.timeoutMs(packet)
        : this.timeoutMs;
    this.timer = setTimeout(() => this.abort('ACK_TIMEOUT'), timeout);
    void this.send(packet).catch(error =>
      this.abort(String(error?.message || error)),
    );
  }
  abort(message: string) {
    if (this.dead || this.completed) {
      return;
    }
    this.cancel();
    this.failed(message);
  }
  cancel() {
    if (this.dead || this.completed) return;
    this.dead = true;
    clearTimeout(this.timer);
    // Cancellation releases the receiver without changing any text already typed.
    void this.send({
      type: 'cancel',
      session: this.id,
      seq: ++this.sequence,
    }).catch(() => {});
  }
  dispose() {
    this.dead = true;
    clearTimeout(this.timer);
  }
}
