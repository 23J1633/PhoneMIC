package main

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

type fakeKeyboard struct {
	text      string
	calls     int
	changed   bool
	shortcuts []keyboardShortcut
	clears    int
}

func (f *fakeKeyboard) Clear(_ inputTarget) error {
	if f.changed {
		return errors.New("focus changed")
	}
	f.text = ""
	f.clears++
	return nil
}

func (f *fakeKeyboard) Shortcut(_ inputTarget, s keyboardShortcut) error {
	if f.changed {
		return errors.New("focus changed")
	}
	f.shortcuts = append(f.shortcuts, s)
	return nil
}
func (f *fakeKeyboard) Capture() (inputTarget, error) { return inputTarget{1, 2}, nil }
func (f *fakeKeyboard) Apply(_ inputTarget, old, next string) error {
	if f.changed {
		return errors.New("focus changed")
	}
	if f.text != old {
		return errors.New("unexpected previous text")
	}
	f.text = next
	f.calls++
	return nil
}
func TestClearRemovesTheEntireFieldOnce(t *testing.T) {
	k := &fakeKeyboard{text: "Text that predates this phone session\n已有内容"}
	e := newEngine(k)
	e.handle("phone", message{Type: "begin", Session: "clear-field-1", Seq: 1})
	clear := message{Type: "clear", Session: "clear-field-1", Seq: 2, Final: true}
	for i := 0; i < 2; i++ {
		if r := e.handle("phone", clear); r.Type != "ack" || !r.Final {
			t.Fatal(r)
		}
	}
	if k.text != "" || k.clears != 1 || len(k.shortcuts) != 0 {
		t.Fatalf("Unexpected clear result: %+v", k)
	}
}

func TestClearRequiresFreshCapturedFocusAndAnIndependentSession(t *testing.T) {
	for _, scenario := range []string{"missing", "cancelled", "expired", "focus-changed", "after-text", "incomplete"} {
		t.Run(scenario, func(t *testing.T) {
			k := &fakeKeyboard{text: "Keep this text"}
			e := newEngine(k)
			if scenario != "missing" {
				e.handle("phone", message{Type: "begin", Session: "clear-field-1", Seq: 1})
			}
			switch scenario {
			case "cancelled":
				e.handle("phone", message{Type: "cancel", Session: "clear-field-1", Seq: 2})
			case "expired":
				e.sessions["phone:clear-field-1"].Last = time.Now().Add(-11 * time.Second)
			case "focus-changed":
				k.changed = true
			case "after-text":
				e.sessions["phone:clear-field-1"].Seq = 2
			}
			seq := 2
			if scenario == "after-text" {
				seq = 3
			}
			r := e.handle("phone", message{Type: "clear", Session: "clear-field-1", Seq: seq, Final: scenario != "incomplete"})
			if r.Type != "error" || k.text != "Keep this text" || k.clears != 0 {
				t.Fatalf("Unsafe clear: %+v %+v", r, k)
			}
		})
	}
}

func TestStreamCorrectionAndDeduplication(t *testing.T) {
	k := &fakeKeyboard{}
	e := newEngine(k)
	for _, m := range []message{{Type: "begin", Session: "session-1", Seq: 1}, {Type: "text", Session: "session-1", Seq: 2, Text: "你好世"}, {Type: "text", Session: "session-1", Seq: 3, Text: "你好世界。", Final: true}, {Type: "text", Session: "session-1", Seq: 3, Text: "你好世界。", Final: true}} {
		if r := e.handle("phone", m); r.Type != "ack" {
			t.Fatal(r)
		}
	}
	if k.text != "你好世界。" || k.calls != 2 {
		t.Fatalf("duplicate injected: %+v", k)
	}
}
func TestCompletionShortcutRunsOnceAfterFinalText(t *testing.T) {
	k := &fakeKeyboard{}
	e := newEngine(k)
	e.handle("phone", message{Type: "begin", Session: "shortcut-1", Seq: 1})
	e.handle("phone", message{Type: "text", Session: "shortcut-1", Seq: 2, Text: "draft"})
	if len(k.shortcuts) != 0 {
		t.Fatal("Shortcut ran during partial input")
	}
	final := message{Type: "text", Session: "shortcut-1", Seq: 3, Text: "final text", Final: true, Shortcut: &keyboardShortcut{Key: "PageDown", Modifiers: []string{"Control"}}}
	for i := 0; i < 2; i++ {
		if r := e.handle("phone", final); r.Type != "ack" {
			t.Fatal(r)
		}
	}
	if k.text != "final text" || k.calls != 2 || len(k.shortcuts) != 1 || k.shortcuts[0].Key != "PageDown" {
		t.Fatalf("Unexpected final shortcut: %+v", k)
	}
}
func TestCompletionShortcutDoesNotRunForEmptyOrFailedInput(t *testing.T) {
	for _, tc := range []struct {
		text    string
		changed bool
	}{{"", false}, {"blocked", true}} {
		k := &fakeKeyboard{changed: tc.changed}
		e := newEngine(k)
		e.handle("phone", message{Type: "begin", Session: "shortcut-1", Seq: 1})
		e.handle("phone", message{Type: "text", Session: "shortcut-1", Seq: 2, Text: tc.text, Final: true, Shortcut: &keyboardShortcut{Key: "Enter"}})
		if len(k.shortcuts) != 0 {
			t.Fatal("Shortcut ran without successful nonempty input")
		}
	}
}
func TestUncommonShortcutKeysAndModifiers(t *testing.T) {
	for key, want := range map[string]uint16{"PageUp": 33, "PageDown": 34, "Home": 36, "End": 35, "F24": 135, "Numpad9": 105, "NumpadEnter": 13, "ScrollLock": 145} {
		if got := virtualKey(key); got != want {
			t.Fatalf("%s: got %d, want %d", key, got, want)
		}
		if err := (keyboardShortcut{Key: key, Modifiers: []string{"Control", "Shift"}}).validate(); err != nil {
			t.Fatal(err)
		}
	}
	if (keyboardShortcut{Key: "F25"}).validate() == nil {
		t.Fatal("Accepted an unavailable key")
	}
	if (keyboardShortcut{Key: "Enter", Modifiers: []string{"Control", "Control"}}).validate() == nil {
		t.Fatal("Accepted duplicate modifiers")
	}
	e := newEngine(&fakeKeyboard{})
	if r := e.handle("phone", message{Type: "begin", Session: "shortcut-1", Seq: 1, Shortcut: &keyboardShortcut{Key: "Enter"}}); r.Code != "PROTOCOL" {
		t.Fatal("Accepted a shortcut before final text")
	}
}
func TestSessionBoundaries(t *testing.T) {
	k := &fakeKeyboard{}
	e := newEngine(k)
	if r := e.handle("phone", message{Type: "text", Session: "session-1", Seq: 1, Text: "unsafe"}); r.Type != "error" {
		t.Fatal("accepted text without begin")
	}
	e.handle("phone", message{Type: "begin", Session: "session-1", Seq: 1})
	if r := e.handle("other", message{Type: "begin", Session: "session-2", Seq: 1}); r.Code != "BUSY" {
		t.Fatal(r)
	}
	if r := e.handle("phone", message{Type: "text", Session: "session-1", Seq: 3, Text: "gap"}); r.Code != "SEQUENCE" {
		t.Fatal(r)
	}
	k.changed = true
	if r := e.handle("phone", message{Type: "text", Session: "session-1", Seq: 2, Text: "wrong window"}); r.Type != "error" {
		t.Fatal("injected into changed window")
	}
	if k.calls != 0 {
		t.Fatal("injected after focus change")
	}
}
func TestAbandonDoesNotReplay(t *testing.T) {
	e := newEngine(&fakeKeyboard{})
	e.handle("phone", message{Type: "begin", Session: "session-1", Seq: 1})
	e.abandon("phone")
	if r := e.handle("phone", message{Type: "text", Session: "session-1", Seq: 2, Text: "retry"}); r.Code != "SESSION_FAILED" {
		t.Fatal(r)
	}
}
func TestCancellationReleasesSessionAndPreservesText(t *testing.T) {
	k := &fakeKeyboard{}
	e := newEngine(k)
	e.handle("phone", message{Type: "begin", Session: "session-1", Seq: 1})
	e.handle("phone", message{Type: "text", Session: "session-1", Seq: 2, Text: "already typed"})
	e.handle("other", message{Type: "cancel", Session: "session-1", Seq: 3})
	if r := e.handle("other", message{Type: "begin", Session: "session-2", Seq: 1}); r.Code != "BUSY" {
		t.Fatal("Another phone cancelled this session")
	}
	if r := e.handle("phone", message{Type: "cancel", Session: "session-1", Seq: 4}); r.Type != "ack" {
		t.Fatal(r)
	}
	if k.text != "already typed" || k.calls != 1 {
		t.Fatal("Cancellation changed committed text")
	}
	if r := e.handle("other", message{Type: "begin", Session: "session-2", Seq: 1}); r.Type != "ack" {
		t.Fatal("Cancelled session still blocks dictation", r)
	}
}
func TestUnicodeReplacement(t *testing.T) {
	for _, tc := range []struct {
		old, next string
		remove    int
		append    string
	}{{"你好世", "你好世界", 0, "界"}, {"hello word", "hello world", 1, "ld"}, {"cafe\u0301", "café", 1, "é"}, {"hello", "", 5, ""}} {
		n, s := replacement(tc.old, tc.next)
		if n != tc.remove || s != tc.append {
			t.Fatalf("%+v got %d %q", tc, n, s)
		}
	}
}
func TestAutomaticLinkAndControlAccess(t *testing.T) {
	s, _, err := loadReceiver(t.TempDir(), &fakeKeyboard{})
	if err != nil {
		t.Fatal(err)
	}
	pair := func(id, pin string) *httptest.ResponseRecorder {
		body, _ := json.Marshal(map[string]string{"clientId": id, "name": "Android", "fingerprint": pin})
		r := httptest.NewRequest("POST", "/pair", strings.NewReader(string(body)))
		w := httptest.NewRecorder()
		s.api().ServeHTTP(w, r)
		return w
	}
	if w := pair("phone-identity-123456", "wrong"); w.Code != 403 {
		t.Fatal("Accepted a changed certificate")
	}
	first := pair("phone-identity-123456", s.fingerprint)
	if first.Code != 200 {
		t.Fatal(first.Body.String())
	}
	var original map[string]any
	_ = json.Unmarshal(first.Body.Bytes(), &original)
	repeat := pair("phone-identity-123456", s.fingerprint)
	var repeated map[string]any
	_ = json.Unmarshal(repeat.Body.Bytes(), &repeated)
	if repeat.Code != 200 || original["token"] != repeated["token"] {
		t.Fatal("Remembered phone did not reconnect automatically")
	}
	if w := pair("another-phone-123456", s.fingerprint); w.Code != 409 {
		t.Fatal("Another phone replaced the remembered device")
	}
	r := httptest.NewRequest("GET", "http://127.0.0.1:8766/status", nil)
	w := httptest.NewRecorder()
	s.control().ServeHTTP(w, r)
	if w.Code != 403 {
		t.Fatal("Test control exposed without cookie")
	}
	r = httptest.NewRequest("POST", "http://127.0.0.1:8766/revoke", nil)
	r.AddCookie(&http.Cookie{Name: "phonemic", Value: s.uiSecret})
	w = httptest.NewRecorder()
	s.control().ServeHTTP(w, r)
	if w.Code != 403 {
		t.Fatal("CSRF accepted")
	}
	r = httptest.NewRequest("GET", "/ws", nil)
	w = httptest.NewRecorder()
	s.api().ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatal("Unauthenticated socket accepted")
	}
}
