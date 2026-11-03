package main

import (
	"bytes"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sync/atomic"
	"syscall"
	"time"
	"unicode/utf16"
	"unsafe"
)

//go:embed app.ico
var trayIconData []byte

var trayWindow atomic.Uintptr
var trayStatus atomic.Value

func setTrayStatus(status string) {
	trayStatus.Store(status)
	if hwnd := trayWindow.Load(); hwnd != 0 {
		postMessage.Call(hwnd, 0x8002, 0, 0)
	}
}

func prepareTrayIcon(directory string) string {
	target := filepath.Join(directory, "app.ico")
	previous, _ := os.ReadFile(target)
	if !bytes.Equal(previous, trayIconData) {
		_ = os.WriteFile(target, trayIconData, 0600)
	}
	return target
}

var (
	user32                   = syscall.NewLazyDLL("user32.dll")
	shell32                  = syscall.NewLazyDLL("shell32.dll")
	kernel32                 = syscall.NewLazyDLL("kernel32.dll")
	getForegroundWindow      = user32.NewProc("GetForegroundWindow")
	getWindowThreadProcessID = user32.NewProc("GetWindowThreadProcessId")
	getGUIThreadInfo         = user32.NewProc("GetGUIThreadInfo")
	sendInput                = user32.NewProc("SendInput")
	createWindow             = user32.NewProc("CreateWindowExW")
	defWindowProc            = user32.NewProc("DefWindowProcW")
	destroyWindow            = user32.NewProc("DestroyWindow")
	getMessage               = user32.NewProc("GetMessageW")
	translateMessage         = user32.NewProc("TranslateMessage")
	dispatchMessage          = user32.NewProc("DispatchMessageW")
	postMessage              = user32.NewProc("PostMessageW")
)

func wide(text string) *uint16 { p, _ := syscall.UTF16PtrFromString(text); return p }

type point struct{ X, Y int32 }
type rect struct{ Left, Top, Right, Bottom int32 }
type guiInfo struct {
	Size, Flags                                   uint32
	Active, Focus, Capture, Menu, MoveSize, Caret uintptr
	CaretRect                                     rect
}
type winInput struct {
	Type             uint32
	Align            uint32
	VK, Scan         uint16
	Flags, Time, Pad uint32
	Extra            uintptr
	UnionPadding     [8]byte
}
type windowsKeyboard struct{}

func newKeyboard() keyboard { return windowsKeyboard{} }
func (windowsKeyboard) Capture() (inputTarget, error) {
	hwnd, _, _ := getForegroundWindow.Call()
	if hwnd == 0 {
		return inputTarget{}, errors.New("Select a text field on your computer first")
	}
	thread, _, _ := getWindowThreadProcessID.Call(hwnd, 0)
	info := guiInfo{Size: uint32(unsafe.Sizeof(guiInfo{}))}
	ok, _, _ := getGUIThreadInfo.Call(thread, uintptr(unsafe.Pointer(&info)))
	if ok == 0 {
		return inputTarget{}, errors.New("Cannot read the active text field")
	}
	return inputTarget{Window: hwnd, Control: info.Focus}, nil
}
func (k windowsKeyboard) Apply(target inputTarget, old, next string) error {
	current, err := k.Capture()
	if err != nil || current != target {
		return errors.New("The active window changed. Select your text field and start again")
	}
	remove, appendText := replacement(old, next)
	events := make([]winInput, 0, 512)
	flush := func() error {
		if len(events) == 0 {
			return nil
		}
		current, err := k.Capture()
		if err != nil || current != target {
			return errors.New("The active text field changed during input")
		}
		sent, _, callErr := sendInput.Call(uintptr(len(events)), uintptr(unsafe.Pointer(&events[0])), unsafe.Sizeof(winInput{}))
		if sent != uintptr(len(events)) {
			return fmt.Errorf("Windows blocked keyboard input (%d/%d): %v", sent, len(events), callErr)
		}
		events = events[:0]
		return nil
	}
	key := func(vk, scan uint16, flags uint32) error {
		events = append(events, winInput{Type: 1, VK: vk, Scan: scan, Flags: flags}, winInput{Type: 1, VK: vk, Scan: scan, Flags: flags | 2})
		if len(events) == cap(events) {
			return flush()
		}
		return nil
	}
	for i := 0; i < remove; i++ {
		if err := key(8, 0, 0); err != nil {
			return err
		}
	}
	for _, character := range appendText {
		switch character {
		case '\n':
			if err := key(13, 0, 0); err != nil {
				return err
			}
		case '\t':
			if err := key(9, 0, 0); err != nil {
				return err
			}
		default:
			if character <= 0xffff {
				if err := key(0, uint16(character), 4); err != nil {
					return err
				}
			} else {
				high, low := utf16.EncodeRune(character)
				if err := key(0, uint16(high), 4); err != nil {
					return err
				}
				if err := key(0, uint16(low), 4); err != nil {
					return err
				}
			}
		}
	}
	return flush()
}

func openBrowser(url string) {
	cmd := exec.Command("rundll32.exe", "url.dll,FileProtocolHandler", url)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	_ = cmd.Start()
}
func (k windowsKeyboard) Shortcut(target inputTarget, shortcut keyboardShortcut) error {
	if err := shortcut.validate(); err != nil {
		return err
	}
	current, err := k.Capture()
	if err != nil || current != target {
		return errors.New("The active text field changed before the shortcut")
	}
	var storage [10]winInput
	events := storage[:0]
	appendKey := func(vk uint16, flags uint32) { events = append(events, winInput{Type: 1, VK: vk, Flags: flags}) }
	modifierKey := func(name string) uint16 {
		return map[string]uint16{"Control": 17, "Alt": 18, "Shift": 16, "Meta": 91}[name]
	}
	modifierFlags := func(name string) uint32 {
		if name == "Meta" {
			return 1
		}
		return 0
	}
	for _, mod := range shortcut.Modifiers {
		appendKey(modifierKey(mod), modifierFlags(mod))
	}
	extended := uint32(0)
	switch shortcut.Key {
	case "ArrowLeft", "ArrowUp", "ArrowDown", "ArrowRight", "Home", "End", "PageUp", "PageDown", "Insert", "Delete", "PrintScreen", "NumpadEnter", "NumpadDivide", "NumLock", "ContextMenu":
		extended = 1
	}
	appendKey(virtualKey(shortcut.Key), extended)
	appendKey(virtualKey(shortcut.Key), extended|2)
	for i := len(shortcut.Modifiers) - 1; i >= 0; i-- {
		mod := shortcut.Modifiers[i]
		appendKey(modifierKey(mod), modifierFlags(mod)|2)
	}
	sent, _, callErr := sendInput.Call(uintptr(len(events)), uintptr(unsafe.Pointer(&events[0])), unsafe.Sizeof(winInput{}))
	if sent != uintptr(len(events)) {
		for _, mod := range shortcut.Modifiers {
			release := winInput{Type: 1, VK: modifierKey(mod), Flags: modifierFlags(mod) | 2}
			sendInput.Call(1, uintptr(unsafe.Pointer(&release)), unsafe.Sizeof(release))
		}
		return fmt.Errorf("Windows blocked the shortcut (%d/%d): %v", sent, len(events), callErr)
	}
	return nil
}
func showError(text string) {
	_, _, _ = user32.NewProc("MessageBoxW").Call(0, uintptr(unsafe.Pointer(wide(text))), uintptr(unsafe.Pointer(wide("PhoneMIC"))), 0x10)
}
func setStartup(enabled bool) error {
	key := `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`
	var cmd *exec.Cmd
	if enabled {
		exe, err := os.Executable()
		if err != nil {
			return err
		}
		cmd = exec.Command("reg.exe", "add", key, "/v", "PhoneMIC", "/t", "REG_SZ", "/d", `"`+exe+`" --background`, "/f")
	} else {
		cmd = exec.Command("reg.exe", "delete", key, "/v", "PhoneMIC", "/f")
	}
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	return cmd.Run()
}

type windowClass struct {
	Size, Style                        uint32
	Proc                               uintptr
	ClassExtra, WindowExtra            int32
	Instance, Icon, Cursor, Background uintptr
	Menu, Name                         *uint16
	IconSmall                          uintptr
}
type winMessage struct {
	Window         uintptr
	Message        uint32
	WParam, LParam uintptr
	Time           uint32
	Point          point
	Private        uint32
}
type notifyIcon struct {
	Size                uint32
	Window              uintptr
	ID, Flags, Callback uint32
	Icon                uintptr
	Tip                 [128]uint16
	State, StateMask    uint32
	Info                [256]uint16
	Version             uint32
	InfoTitle           [64]uint16
	InfoFlags           uint32
	GUID                [16]byte
	Balloon             uintptr
}

func runTray(iconFile string, reset func(), quit chan struct{}) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	instance, _, _ := kernel32.NewProc("GetModuleHandleW").Call(0)
	user32.NewProc("SetProcessDPIAware").Call()
	iconSize, _, _ := user32.NewProc("GetSystemMetrics").Call(49)
	icon, _, _ := user32.NewProc("LoadImageW").Call(0, uintptr(unsafe.Pointer(wide(iconFile))), 1, iconSize, iconSize, 0x10)
	if icon == 0 {
		icon, _, _ = user32.NewProc("LoadIconW").Call(0, 32516)
	}
	defer user32.NewProc("DestroyIcon").Call(icon)
	className := wide("PhoneMICTray")
	var notify notifyIcon
	taskbarCreated, _, _ := user32.NewProc("RegisterWindowMessageW").Call(uintptr(unsafe.Pointer(wide("TaskbarCreated"))))
	callback := syscall.NewCallback(func(hwnd uintptr, msg uint32, wparam, lparam uintptr) uintptr {
		if msg == uint32(taskbarCreated) {
			shell32.NewProc("Shell_NotifyIconW").Call(0, uintptr(unsafe.Pointer(&notify)))
			return 0
		}
		switch msg {
		case 0x8002:
			status, _ := trayStatus.Load().(string)
			notify.Tip = [128]uint16{}
			copy(notify.Tip[:127], utf16.Encode([]rune(status)))
			shell32.NewProc("Shell_NotifyIconW").Call(1, uintptr(unsafe.Pointer(&notify)))
			return 0
		case 0x8001:
			if lparam == 0x205 {
				menu, _, _ := user32.NewProc("CreatePopupMenu").Call()
				user32.NewProc("AppendMenuW").Call(menu, 0, 1, uintptr(unsafe.Pointer(wide("重新连接手机"))))
				user32.NewProc("AppendMenuW").Call(menu, 0, 2, uintptr(unsafe.Pointer(wide("退出 PhoneMIC"))))
				var cursor point
				user32.NewProc("GetCursorPos").Call(uintptr(unsafe.Pointer(&cursor)))
				user32.NewProc("SetForegroundWindow").Call(hwnd)
				selected, _, _ := user32.NewProc("TrackPopupMenu").Call(menu, 0x0100|0x0002, uintptr(cursor.X), uintptr(cursor.Y), 0, hwnd, 0)
				user32.NewProc("DestroyMenu").Call(menu)
				if selected == 1 {
					reset()
				}
				if selected == 2 {
					select {
					case <-quit:
					default:
						close(quit)
					}
				}
			}
		case 0x0010:
			destroyWindow.Call(hwnd)
			return 0
		case 0x0002:
			user32.NewProc("PostQuitMessage").Call(0)
			return 0
		}
		result, _, _ := defWindowProc.Call(hwnd, uintptr(msg), wparam, lparam)
		return result
	})
	wc := windowClass{Size: uint32(unsafe.Sizeof(windowClass{})), Proc: callback, Instance: instance, Name: className}
	user32.NewProc("RegisterClassExW").Call(uintptr(unsafe.Pointer(&wc)))
	hwnd, _, _ := createWindow.Call(0, uintptr(unsafe.Pointer(className)), uintptr(unsafe.Pointer(wide("PhoneMIC"))), 0, 0, 0, 0, 0, 0, 0, instance, 0)
	if hwnd == 0 {
		<-quit
		return
	}
	notify = notifyIcon{Size: uint32(unsafe.Sizeof(notifyIcon{})), Window: hwnd, ID: 1, Flags: 1 | 2 | 4 | 0x20, Callback: 0x8001, Icon: icon, GUID: [16]byte{0x44, 0x60, 0xc4, 0x2d, 0x1b, 0x2b, 0x48, 0x67, 0x82, 0xc9, 0xe1, 0x48, 0xe5, 0x10, 0x0f, 0x77}}
	status, _ := trayStatus.Load().(string)
	if status == "" {
		status = "PhoneMIC · 等待手机"
	}
	copy(notify.Tip[:127], utf16.Encode([]rune(status)))
	trayWindow.Store(hwnd)
	defer trayWindow.Store(0)
	shell32.NewProc("Shell_NotifyIconW").Call(0, uintptr(unsafe.Pointer(&notify)))
	defer shell32.NewProc("Shell_NotifyIconW").Call(2, uintptr(unsafe.Pointer(&notify)))
	go func() { <-quit; postMessage.Call(hwnd, 0x0010, 0, 0) }()
	var msg winMessage
	for {
		ok, _, _ := getMessage.Call(uintptr(unsafe.Pointer(&msg)), 0, 0, 0)
		if int32(ok) <= 0 {
			break
		}
		translateMessage.Call(uintptr(unsafe.Pointer(&msg)))
		dispatchMessage.Call(uintptr(unsafe.Pointer(&msg)))
	}
}

func platformSelfTest(output string) (err error) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	result := map[string]any{"passed": false, "expected": "你好，PhoneMIC 4090.\nEnglish input works.", "actual": ""}
	defer func() {
		if err != nil {
			result["error"] = err.Error()
		}
		b, _ := json.MarshalIndent(result, "", "  ")
		_ = os.WriteFile(output, b, 0600)
	}()
	previous, _, _ := getForegroundWindow.Call()
	parent, _, _ := createWindow.Call(0, uintptr(unsafe.Pointer(wide("STATIC"))), uintptr(unsafe.Pointer(wide("PhoneMIC keyboard verification"))), 0x10CF0000, 100, 100, 640, 240, 0, 0, 0, 0)
	if parent == 0 {
		return errors.New("Cannot create verification window")
	}
	defer destroyWindow.Call(parent)
	child, _, _ := createWindow.Call(0x200, uintptr(unsafe.Pointer(wide("EDIT"))), 0, 0x50000004, 12, 12, 600, 170, parent, 0, 0, 0)
	user32.NewProc("SetForegroundWindow").Call(parent)
	user32.NewProc("SetFocus").Call(child)
	defer user32.NewProc("SetForegroundWindow").Call(previous)
	pump := func() {
		deadline := time.Now().Add(300 * time.Millisecond)
		for time.Now().Before(deadline) {
			var msg winMessage
			ok, _, _ := user32.NewProc("PeekMessageW").Call(uintptr(unsafe.Pointer(&msg)), 0, 0, 0, 1)
			if ok != 0 {
				translateMessage.Call(uintptr(unsafe.Pointer(&msg)))
				dispatchMessage.Call(uintptr(unsafe.Pointer(&msg)))
			} else {
				time.Sleep(time.Millisecond)
			}
		}
	}
	pump()
	k := windowsKeyboard{}
	target, err := k.Capture()
	if err != nil {
		return err
	}
	if target.Window != parent {
		return errors.New("Verification window did not receive focus")
	}
	if err = k.Apply(target, "", "你好，PhoneMIC 4099."); err != nil {
		return err
	}
	pump()
	if err = k.Apply(target, "你好，PhoneMIC 4099.", result["expected"].(string)); err != nil {
		return err
	}
	pump()
	buffer := make([]uint16, 1024)
	user32.NewProc("GetWindowTextW").Call(child, uintptr(unsafe.Pointer(&buffer[0])), uintptr(len(buffer)))
	actual := syscall.UTF16ToString(buffer)
	result["actual"] = actual
	if actual != "你好，PhoneMIC 4090.\r\nEnglish input works." {
		return errors.New("Unicode input or streaming correction did not match")
	}
	result["passed"] = true
	return nil
}

func focusVerificationWindow(parent, child uintptr) {
	foreground, _, _ := getForegroundWindow.Call()
	foregroundThread, _, _ := getWindowThreadProcessID.Call(foreground, 0)
	currentThread, _, _ := kernel32.NewProc("GetCurrentThreadId").Call()
	attached := uintptr(0)
	if foregroundThread != 0 && foregroundThread != currentThread {
		attached, _, _ = user32.NewProc("AttachThreadInput").Call(currentThread, foregroundThread, 1)
	}
	if attached != 0 {
		defer user32.NewProc("AttachThreadInput").Call(currentThread, foregroundThread, 0)
	}
	user32.NewProc("ShowWindow").Call(parent, 9)
	user32.NewProc("BringWindowToTop").Call(parent)
	user32.NewProc("SetForegroundWindow").Call(parent)
	user32.NewProc("SetFocus").Call(child)
}

func openVerificationWindow(output string, quit chan struct{}) (inputTarget, error) {
	type outcome struct {
		target inputTarget
		err    error
	}
	ready := make(chan outcome, 1)
	go func() {
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		parent, _, _ := createWindow.Call(0, uintptr(unsafe.Pointer(wide("STATIC"))), uintptr(unsafe.Pointer(wide("PhoneMIC device integration · scratch input"))), 0x10CF0000, 120, 120, 720, 320, 0, 0, 0, 0)
		if parent == 0 {
			ready <- outcome{err: errors.New("Cannot create verification window")}
			return
		}
		defer destroyWindow.Call(parent)
		child, _, _ := createWindow.Call(0x200, uintptr(unsafe.Pointer(wide("EDIT"))), 0, 0x50000004, 12, 12, 680, 250, parent, 0, 0, 0)
		focusVerificationWindow(parent, child)
		target, err := (windowsKeyboard{}).Capture()
		if err == nil && target.Window != parent {
			err = errors.New("Verification window did not receive focus")
		}
		ready <- outcome{target, err}
		if err != nil {
			return
		}
		ticker := time.NewTicker(40 * time.Millisecond)
		defer ticker.Stop()
		previous := "\x00"
		for {
			select {
			case <-quit:
				return
			case <-ticker.C:
			}
			if _, err := os.Stat(output + ".focus"); err == nil {
				_ = os.Remove(output + ".focus")
				focusVerificationWindow(parent, child)
			}
			for {
				var msg winMessage
				ok, _, _ := user32.NewProc("PeekMessageW").Call(uintptr(unsafe.Pointer(&msg)), 0, 0, 0, 1)
				if ok == 0 {
					break
				}
				translateMessage.Call(uintptr(unsafe.Pointer(&msg)))
				dispatchMessage.Call(uintptr(unsafe.Pointer(&msg)))
			}
			buffer := make([]uint16, 40000)
			user32.NewProc("GetWindowTextW").Call(child, uintptr(unsafe.Pointer(&buffer[0])), uintptr(len(buffer)))
			text := syscall.UTF16ToString(buffer)
			if text != previous {
				data, _ := json.MarshalIndent(map[string]any{"actual": text, "window": parent, "control": child}, "", "  ")
				_ = os.WriteFile(output, data, 0600)
				previous = text
			}
		}
	}()
	result := <-ready
	return result.target, result.err
}
