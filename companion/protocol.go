package main

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"

	"github.com/rivo/uniseg"
)

type message struct {
	Type     string            `json:"type"`
	Session  string            `json:"session,omitempty"`
	Seq      int               `json:"seq,omitempty"`
	Text     string            `json:"text,omitempty"`
	Final    bool              `json:"final,omitempty"`
	Nonce    int64             `json:"nonce,omitempty"`
	Shortcut *keyboardShortcut `json:"shortcut,omitempty"`
}

type response struct {
	Type     string `json:"type"`
	Session  string `json:"session,omitempty"`
	Seq      int    `json:"seq,omitempty"`
	Final    bool   `json:"final,omitempty"`
	Code     string `json:"code,omitempty"`
	Message  string `json:"message,omitempty"`
	Nonce    int64  `json:"nonce,omitempty"`
	Instance string `json:"instance,omitempty"`
}

type inputTarget struct{ Window, Control uintptr }

type keyboard interface {
	Capture() (inputTarget, error)
	Apply(inputTarget, string, string) error
	Shortcut(inputTarget, keyboardShortcut) error
}

type keyboardShortcut struct {
	Key       string   `json:"key"`
	Modifiers []string `json:"modifiers"`
}

func virtualKey(key string) uint16 {
	if len(key) == 1 && ((key[0] >= 'A' && key[0] <= 'Z') || (key[0] >= '0' && key[0] <= '9')) {
		return uint16(key[0])
	}
	if strings.HasPrefix(key, "F") {
		n, err := strconv.Atoi(key[1:])
		if err == nil && n >= 1 && n <= 24 {
			return uint16(0x70 + n - 1)
		}
	}
	if strings.HasPrefix(key, "Numpad") && len(key) == 7 && key[6] >= '0' && key[6] <= '9' {
		return uint16(0x60 + key[6] - '0')
	}
	return map[string]uint16{"Enter": 13, "Tab": 9, "Space": 32, "Escape": 27, "Backspace": 8, "Delete": 46,
		"ArrowLeft": 37, "ArrowUp": 38, "ArrowRight": 39, "ArrowDown": 40, "Home": 36, "End": 35, "PageUp": 33, "PageDown": 34,
		"Insert": 45, "CapsLock": 20, "PrintScreen": 44, "Pause": 19, "Minus": 189, "Equal": 187, "BracketLeft": 219, "BracketRight": 221,
		"Backslash": 220, "Semicolon": 186, "Quote": 222, "Backquote": 192, "Comma": 188, "Period": 190, "Slash": 191,
		"ScrollLock": 145, "NumLock": 144, "ContextMenu": 93, "NumpadEnter": 13, "NumpadDecimal": 110, "NumpadAdd": 107,
		"NumpadSubtract": 109, "NumpadMultiply": 106, "NumpadDivide": 111}[key]
}
func (s keyboardShortcut) validate() error {
	if virtualKey(s.Key) == 0 || len(s.Modifiers) > 4 {
		return errors.New("Unsupported keyboard shortcut")
	}
	seen := map[string]bool{}
	for _, mod := range s.Modifiers {
		if (mod != "Control" && mod != "Alt" && mod != "Shift" && mod != "Meta") || seen[mod] {
			return errors.New("Invalid shortcut modifiers")
		}
		seen[mod] = true
	}
	return nil
}

type inputSession struct {
	Target inputTarget
	Text   string
	Seq    int
	Final  bool
	Failed bool
	Last   time.Time
}

type engine struct {
	mu       sync.Mutex
	sessions map[string]*inputSession
	keyboard keyboard
}

func newEngine(k keyboard) *engine {
	return &engine{sessions: make(map[string]*inputSession), keyboard: k}
}

func (e *engine) handle(owner string, m message) response {
	r := response{Type: "ack", Session: m.Session, Seq: m.Seq, Final: m.Final}
	fail := func(code, text string) response {
		r.Type, r.Code, r.Message = "error", code, text
		return r
	}
	if m.Type == "ping" {
		return response{Type: "pong", Nonce: m.Nonce}
	}
	if len(m.Session) < 8 || len(m.Session) > 96 || m.Seq < 1 || m.Seq > 1000000 {
		return fail("PROTOCOL", "Invalid session")
	}
	if m.Type != "begin" && m.Type != "text" && m.Type != "cancel" {
		return fail("PROTOCOL", "Unknown message")
	}
	if len(m.Text) > 60000 || strings.IndexFunc(m.Text, func(r rune) bool {
		return unicode.IsControl(r) && r != '\n' && r != '\t'
	}) >= 0 {
		return fail("PROTOCOL", "Text exceeds the limit or contains control characters")
	}
	if m.Shortcut != nil {
		if m.Type != "text" || !m.Final {
			return fail("PROTOCOL", "Shortcuts are only allowed after final text")
		}
		if err := m.Shortcut.validate(); err != nil {
			return fail("PROTOCOL", err.Error())
		}
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	key := owner + ":" + m.Session
	s, found := e.sessions[key]
	if m.Type == "cancel" {
		if found {
			s.Failed = true
			s.Text = ""
		}
		return r
	}
	if !found {
		if m.Type != "begin" || m.Seq != 1 {
			return fail("SESSION_LOST", "Start a new dictation session")
		}
		for id, old := range e.sessions {
			if time.Since(old.Last) > 30*time.Minute {
				delete(e.sessions, id)
			}
		}
		if len(e.sessions) >= 1024 {
			return fail("BUSY", "Too many active sessions")
		}
		// A single active session prevents interleaved corrections from two phones.
		for _, other := range e.sessions {
			if !other.Final && !other.Failed && time.Since(other.Last) < 11*time.Minute {
				return fail("BUSY", "Another dictation is in progress")
			}
		}
		target, err := e.keyboard.Capture()
		if err != nil {
			return fail("FOCUS", err.Error())
		}
		s = &inputSession{Target: target, Seq: 1, Last: time.Now()}
		e.sessions[key] = s
		return r
	}
	if s.Failed {
		return fail("SESSION_FAILED", "The previous input failed; start a new session")
	}
	if m.Seq <= s.Seq {
		// Duplicate delivery only repeats the acknowledgement, never the key events.
		return r
	}
	if s.Final || m.Seq != s.Seq+1 || m.Type != "text" {
		return fail("SEQUENCE", "Unexpected message sequence")
	}
	if err := e.keyboard.Apply(s.Target, s.Text, m.Text); err != nil {
		s.Failed = true
		return fail("INPUT", err.Error())
	}
	if m.Shortcut != nil && strings.TrimSpace(m.Text) != "" {
		if err := e.keyboard.Shortcut(s.Target, *m.Shortcut); err != nil {
			s.Failed = true
			return fail("SHORTCUT", err.Error())
		}
	}
	s.Text, s.Seq, s.Final, s.Last = m.Text, m.Seq, m.Final, time.Now()
	if s.Final {
		s.Text = ""
	}
	return r
}

func (e *engine) abandon(owner string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	for id, s := range e.sessions {
		if strings.HasPrefix(id, owner+":") && !s.Final {
			s.Failed = true
			s.Text = ""
		}
	}
}

func replacement(old, next string) (int, string) {
	if strings.HasPrefix(next, old) {
		return 0, next[len(old):]
	}
	split := func(text string) []string {
		g := uniseg.NewGraphemes(text)
		var parts []string
		for g.Next() {
			parts = append(parts, g.Str())
		}
		return parts
	}
	a, b := split(old), split(next)
	common := 0
	for common < len(a) && common < len(b) && a[common] == b[common] {
		common++
	}
	return len(a) - common, strings.Join(b[common:], "")
}

var errUnsupported = errors.New("This receiver supports Windows 10/11")

type restrictedKeyboard struct {
	inner  keyboard
	target inputTarget
}

func (k restrictedKeyboard) Capture() (inputTarget, error) {
	target, err := k.inner.Capture()
	if err != nil {
		return target, err
	}
	if target != k.target {
		return target, fmt.Errorf("Verification focus mismatch: expected %x/%x, active %x/%x", k.target.Window, k.target.Control, target.Window, target.Control)
	}
	return target, nil
}
func (k restrictedKeyboard) Apply(target inputTarget, old, next string) error {
	if target != k.target {
		return errors.New("Input is restricted to the verification window")
	}
	return k.inner.Apply(target, old, next)
}
func (k restrictedKeyboard) Shortcut(target inputTarget, shortcut keyboardShortcut) error {
	if target != k.target {
		return errors.New("Input is restricted to the verification window")
	}
	return k.inner.Shortcut(target, shortcut)
}
