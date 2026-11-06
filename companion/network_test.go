package main

import (
	"crypto/tls"
	"encoding/json"
	"net"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestReceiverTLSOnBothIPFamilies(t *testing.T) {
	s, cert, err := loadReceiver(t.TempDir(), &fakeKeyboard{})
	if err != nil {
		t.Fatal(err)
	}
	listeners, err := listenLAN(0, &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12})
	if err != nil {
		t.Fatal(err)
	}
	server := &http.Server{Handler: s.api()}
	defer server.Close()
	for _, listener := range listeners {
		go server.Serve(listener)
	}
	port := listeners[0].Addr().(*net.TCPAddr).Port
	transport := &http.Transport{Proxy: nil, TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 2 * time.Second}
	for _, host := range []string{"127.0.0.1", "::1"} {
		if host == "::1" {
			if len(listeners) < 2 {
				t.Log("IPv6 listener unavailable on this host")
				continue
			}
			probe, err := net.Listen("tcp6", "[::1]:0")
			if err != nil {
				t.Log("IPv6 unavailable on this host")
				continue
			}
			probe.Close()
		}
		url := "https://" + net.JoinHostPort(host, strconv.Itoa(port)) + "/identity"
		response, err := client.Get(url)
		if err != nil {
			t.Fatalf("%s: %v", host, err)
		}
		var identity map[string]any
		err = json.NewDecoder(response.Body).Decode(&identity)
		response.Body.Close()
		if err != nil || identity["id"] != s.config.ID || identity["fingerprint"] != s.fingerprint {
			t.Fatalf("Invalid identity on %s: %v", host, err)
		}
	}
}

func TestReceiverRejectsAnOccupiedIPv4Port(t *testing.T) {
	occupied, err := net.Listen("tcp4", ":0")
	if err != nil {
		t.Fatal(err)
	}
	defer occupied.Close()
	listeners, err := listenLAN(occupied.Addr().(*net.TCPAddr).Port, &tls.Config{})
	for _, listener := range listeners {
		listener.Close()
	}
	if err == nil {
		t.Fatal("Started an IPv6-only receiver alongside an existing IPv4 receiver")
	}
}

func TestUsableAddressesExcludeVPNFakeIP(t *testing.T) {
	for _, value := range []string{"127.0.0.1", "::1", "0.0.0.0", "::", "198.18.0.1", "198.19.255.254", "169.254.3.4", "fe80::1", "224.0.0.251"} {
		if usableAddress(net.ParseIP(value)) {
			t.Errorf("Unusable address advertised: %s", value)
		}
	}
	for _, value := range []string{"10.3.4.5", "172.20.50.7", "192.168.4.8", "100.64.3.4", "fd12::1", "2001:db8::1"} {
		if !usableAddress(net.ParseIP(value)) {
			t.Errorf("LAN address excluded: %s", value)
		}
	}
}

func TestFirewallRulesStayScopedToReceiver(t *testing.T) {
	program := `C:\Users\O'Brien\PhoneMIC.exe`
	script := firewallScript(program, true)
	for _, expected := range []string{"O''Brien", "-Program $program", "-Profile Any", "-LocalPort 8765", "-LocalPort 45873,5353", "10.0.0.0/8", "fc00::/7", "-PolicyStore PersistentStore"} {
		if !strings.Contains(script, expected) {
			t.Errorf("Missing %s", expected)
		}
	}
	if strings.Contains(script, "-RemoteAddress Any") {
		t.Fatal("Firewall rule exposes service beyond LAN scope")
	}
	check := firewallScript(program, false)
	if strings.Contains(check, "Remove-NetFirewallRule") || strings.Contains(check, "New-NetFirewallRule") {
		t.Fatal("Firewall inspection changes rules")
	}
}

func TestUDPDiscoveryRepliesToRepeatedRequests(t *testing.T) {
	s, _, err := loadReceiver(t.TempDir(), &fakeKeyboard{})
	if err != nil {
		t.Fatal(err)
	}
	listener, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go s.serveDiscovery(listener)
	client, err := net.DialUDP("udp4", nil, listener.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	// Invalid packets must not disrupt valid discovery on this socket.
	for _, packet := range []string{`invalid-json`, `{"service":"other","nonce":"valid-nonce"}`, `{"service":"phonemic-discover-v1","nonce":"short"}`} {
		if _, err := client.Write([]byte(packet)); err != nil {
			t.Fatal(err)
		}
	}
	for _, nonce := range []string{"first-phone-nonce", "first-phone-nonce", "second-phone-nonce"} {
		packet, _ := json.Marshal(map[string]string{"service": "phonemic-discover-v1", "nonce": nonce})
		if _, err := client.Write(packet); err != nil {
			t.Fatal(err)
		}
		client.SetReadDeadline(time.Now().Add(time.Second))
		buffer := make([]byte, 4096)
		n, err := client.Read(buffer)
		if err != nil {
			t.Fatal(err)
		}
		var response map[string]any
		if err := json.Unmarshal(buffer[:n], &response); err != nil {
			t.Fatal(err)
		}
		if response["nonce"] != nonce || response["id"] != s.config.ID || response["fingerprint"] != s.fingerprint || response["port"] != float64(servicePort) {
			t.Fatalf("Invalid discovery reply: %+v", response)
		}
	}
}
