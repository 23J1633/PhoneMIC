package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestAuthenticatedReconnectReplacesStaleSocket(t *testing.T) {
	keyboard := &fakeKeyboard{}
	receiver, _, err := loadReceiver(t.TempDir(), keyboard)
	if err != nil {
		t.Fatal(err)
	}
	receiver.config.Clients["test-token"] = pairedClient{ID: "test-phone"}
	server := httptest.NewServer(receiver.api())
	defer server.Close()
	connect := func() *websocket.Conn {
		t.Helper()
		header := http.Header{"Authorization": []string{"Bearer test-token"}}
		socket, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http")+"/ws", header)
		if err != nil {
			t.Fatal(err)
		}
		socket.SetReadDeadline(time.Now().Add(3 * time.Second))
		var hello response
		if err = socket.ReadJSON(&hello); err != nil || hello.Type != "hello" {
			t.Fatalf("Missing handshake: %v %+v", err, hello)
		}
		return socket
	}
	exchange := func(socket *websocket.Conn, packet message) {
		t.Helper()
		if err := socket.WriteJSON(packet); err != nil {
			t.Fatal(err)
		}
		var reply response
		if err := socket.ReadJSON(&reply); err != nil || reply.Type != "ack" {
			t.Fatalf("Input failed: %v %+v", err, reply)
		}
	}
	first := connect()
	defer first.Close()
	exchange(first, message{Type: "begin", Session: "old-session", Seq: 1})
	second := connect()
	defer second.Close()
	exchange(second, message{Type: "begin", Session: "new-session", Seq: 1})
	exchange(second, message{Type: "text", Session: "new-session", Seq: 2, Text: "你好", Final: true, Shortcut: &keyboardShortcut{Key: "PageDown"}})
	exchange(second, message{Type: "text", Session: "new-session", Seq: 2, Text: "你好", Final: true, Shortcut: &keyboardShortcut{Key: "PageDown"}})
	receiver.mu.Lock()
	active := receiver.active
	receiver.mu.Unlock()
	if active != 1 {
		t.Fatalf("Expected one active connection, got %d", active)
	}
	receiver.input.mu.Lock()
	defer receiver.input.mu.Unlock()
	if keyboard.text != "你好" || keyboard.calls != 1 || len(keyboard.shortcuts) != 1 {
		t.Fatalf("Reconnect duplicated input: %+v", keyboard)
	}
}
