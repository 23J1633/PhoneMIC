package main

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unicode/utf16"
	"unsafe"
)

func powershell(script string, timeout time.Duration) error {
	encoded := utf16.Encode([]rune(script))
	data := make([]byte, len(encoded)*2)
	for i, value := range encoded {
		binary.LittleEndian.PutUint16(data[i*2:], value)
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, filepath.Join(os.Getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe"), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", base64.StdEncoding.EncodeToString(data))
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	if output, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("%w: %s", err, strings.TrimSpace(string(output)))
	}
	return nil
}

func configureFirewall() error {
	program, err := os.Executable()
	if err != nil {
		return err
	}
	return powershell(firewallScript(program, true), 45*time.Second)
}

func firewallReady() bool {
	program, err := os.Executable()
	return err == nil && powershell(firewallScript(program, false), 20*time.Second) == nil
}

func requestFirewall() error {
	program, err := os.Executable()
	if err != nil {
		return err
	}
	// Elevate only the short-lived helper. The receiver continues as the original user.
	result, _, callErr := shell32.NewProc("ShellExecuteW").Call(0, uintptr(unsafe.Pointer(wide("runas"))), uintptr(unsafe.Pointer(wide(program))), uintptr(unsafe.Pointer(wide("--configure-firewall"))), 0, 0)
	if result <= 32 {
		return fmt.Errorf("Windows 未启动网络修复（%d）：%v", result, callErr)
	}
	return nil
}

func prepareFirewall(dir string) {
	if firewallReady() {
		return
	}
	program, _ := os.Executable()
	digest := sha256.Sum256([]byte(strings.ToLower(program)))
	marker := filepath.Join(dir, fmt.Sprintf("firewall-%x.attempt", digest[:8]))
	if _, err := os.Stat(marker); err == nil {
		log.Print("Firewall rules missing; use tray network repair")
		return
	}
	// Avoid repeatedly prompting at startup if UAC was declined or policy disallows local rules.
	if err := os.WriteFile(marker, []byte("v2"), 0600); err != nil {
		log.Print(err)
		return
	}
	if err := requestFirewall(); err != nil {
		log.Print("Firewall setup: ", err)
	}
}

func showNetworkInfo() {
	addresses := localAddresses()
	text := "手机手动连接地址：\n" + strings.Join(addresses, "\n") + "\n\n连接：TCP 8765\n发现：UDP 45873、5353\n"
	if len(addresses) == 0 {
		text += "\n未找到可用地址，请检查 Wi-Fi 或网线。\n"
	}
	if firewallReady() {
		text += "\nPhoneMIC 入站规则已配置。"
	} else {
		text += "\nPhoneMIC 入站规则未就绪，请使用托盘菜单“修复局域网连接”。"
	}
	text += "\n\n若仍无法连接，请检查 VPN 的“允许局域网”、安全软件及路由器的访客/AP 隔离设置。跨 VLAN 需要网络允许访问上述端口，或用可达的 IP / 主机名连接。\n日志：%APPDATA%\\PhoneMIC\\network.log"
	user32.NewProc("MessageBoxW").Call(0, uintptr(unsafe.Pointer(wide(text))), uintptr(unsafe.Pointer(wide("PhoneMIC 连接信息"))), 0x40)
}
