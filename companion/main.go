package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"flag"
	"fmt"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const servicePort = 8765
const discoveryPort = 45873
const controlPort = 8766

type pairedClient struct {
	ID   string `json:"id,omitempty"`
	Name string `json:"name"`
	When string `json:"when"`
}
type configuration struct {
	ID                string                  `json:"id"`
	Clients           map[string]pairedClient `json:"clients"`
	Startup           bool                    `json:"startup"`
	StartupConfigured bool                    `json:"startupConfigured"`
}
type receiver struct {
	mu          sync.Mutex
	dir         string
	config      configuration
	fingerprint string
	instance    string
	uiSecret    string
	active      int
	input       *engine
	quit        chan struct{}
	clients     map[string]*websocket.Conn
}

func randomHex(count int) string {
	b := make([]byte, count)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}
func equal(a, b string) bool { return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1 }

func (s *receiver) saveLocked() error {
	b, err := json.MarshalIndent(s.config, "", "  ")
	if err != nil {
		return err
	}
	p := filepath.Join(s.dir, "config.json")
	if err = os.WriteFile(p+".tmp", b, 0600); err != nil {
		return err
	}
	return os.Rename(p+".tmp", p)
}

func loadReceiver(dir string, k keyboard) (*receiver, tls.Certificate, error) {
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, tls.Certificate{}, err
	}
	s := &receiver{dir: dir, instance: randomHex(16), uiSecret: randomHex(32), input: newEngine(k), quit: make(chan struct{}), clients: make(map[string]*websocket.Conn)}
	p := filepath.Join(dir, "config.json")
	b, err := os.ReadFile(p)
	if err == nil {
		if err = json.Unmarshal(b, &s.config); err != nil {
			return nil, tls.Certificate{}, err
		}
	} else if !os.IsNotExist(err) {
		return nil, tls.Certificate{}, err
	}
	if s.config.ID == "" {
		s.config.ID = randomHex(16)
	}
	if s.config.Clients == nil {
		s.config.Clients = make(map[string]pairedClient)
	}
	if err = s.saveLocked(); err != nil {
		return nil, tls.Certificate{}, err
	}
	certPath, keyPath := filepath.Join(dir, "cert.pem"), filepath.Join(dir, "key.pem")
	if _, err = os.Stat(certPath); os.IsNotExist(err) {
		key, keyErr := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		if keyErr != nil {
			return nil, tls.Certificate{}, keyErr
		}
		serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
		template := &x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: "PhoneMIC " + s.config.ID}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().AddDate(20, 0, 0), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
		der, createErr := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
		if createErr != nil {
			return nil, tls.Certificate{}, createErr
		}
		encodedKey, _ := x509.MarshalPKCS8PrivateKey(key)
		if err = os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: encodedKey}), 0600); err != nil {
			return nil, tls.Certificate{}, err
		}
		if err = os.WriteFile(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0600); err != nil {
			return nil, tls.Certificate{}, err
		}
	}
	cert, err := tls.LoadX509KeyPair(certPath, keyPath)
	if err != nil {
		return nil, cert, err
	}
	digest := sha256.Sum256(cert.Certificate[0])
	s.fingerprint = hex.EncodeToString(digest[:])
	return s, cert, nil
}

func writeJSON(w http.ResponseWriter, status int, data any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(data)
}
func (s *receiver) identity() map[string]any {
	name, _ := os.Hostname()
	return map[string]any{"service": "phonemic", "version": 1, "id": s.config.ID, "name": name, "port": servicePort, "fingerprint": s.fingerprint}
}
func (s *receiver) pair(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST required", 405)
		return
	}
	var req struct{ Name, Fingerprint, ClientID string }
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req) != nil {
		http.Error(w, "Invalid request", 400)
		return
	}
	if len(req.ClientID) < 16 || len(req.ClientID) > 128 || !equal(req.Fingerprint, s.fingerprint) {
		writeJSON(w, 403, map[string]string{"error": "Invalid device identity"})
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for token, client := range s.config.Clients {
		if equal(client.ID, req.ClientID) {
			info := s.identity()
			info["token"] = token
			writeJSON(w, 200, info)
			return
		}
	}
	if len(s.config.Clients) != 0 {
		writeJSON(w, 409, map[string]string{"error": "Receiver is linked to another phone"})
		return
	}
	if len(req.Name) > 100 {
		req.Name = "Android"
	}
	token := randomHex(32)
	s.config.Clients[token] = pairedClient{ID: req.ClientID, Name: req.Name, When: time.Now().Format(time.RFC3339)}
	if err := s.saveLocked(); err != nil {
		delete(s.config.Clients, token)
		writeJSON(w, 500, map[string]string{"error": "Could not save connection"})
		return
	}
	info := s.identity()
	info["token"] = token
	writeJSON(w, 200, info)
}

func (s *receiver) socket(w http.ResponseWriter, r *http.Request) {
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	s.mu.Lock()
	_, allowed := s.config.Clients[token]
	s.mu.Unlock()
	if !allowed || token == "" {
		http.Error(w, "Pair this phone first", 401)
		return
	}
	upgrade := websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return r.Header.Get("Origin") == "" }, ReadBufferSize: 4096, WriteBufferSize: 4096}
	conn, err := upgrade.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	owner := fmt.Sprintf("%s/%p", token, conn)
	s.mu.Lock()
	// A newly authenticated connection replaces a stale socket after network handover.
	_, allowed = s.config.Clients[token]
	if !allowed {
		s.mu.Unlock()
		conn.Close()
		return
	}
	if previous := s.clients[token]; previous != nil {
		s.input.abandon(fmt.Sprintf("%s/%p", token, previous))
		previous.Close()
	}
	s.clients[token] = conn
	s.active = len(s.clients)
	setTrayStatus("PhoneMIC · 手机已连接")
	s.mu.Unlock()
	defer func() {
		conn.Close()
		s.input.abandon(owner)
		s.mu.Lock()
		if s.clients[token] == conn {
			delete(s.clients, token)
		}
		s.active = len(s.clients)
		if s.active == 0 {
			setTrayStatus("PhoneMIC · 等待手机")
		}
		s.mu.Unlock()
	}()
	conn.SetReadLimit(256 * 1024)
	conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	conn.SetPingHandler(func(data string) error {
		conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		return conn.WriteControl(websocket.PongMessage, []byte(data), time.Now().Add(5*time.Second))
	})
	conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if conn.WriteJSON(response{Type: "hello", Instance: s.instance}) != nil {
		return
	}
	for {
		var m message
		if err = conn.ReadJSON(&m); err != nil {
			return
		}
		conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		s.mu.Lock()
		_, allowed = s.config.Clients[token]
		if !allowed || s.clients[token] != conn {
			s.mu.Unlock()
			return
		}
		result := s.input.handle(owner, m)
		s.mu.Unlock()
		if result.Type == "error" {
			setTrayStatus("PhoneMIC · 输入未完成，请检查电脑输入框")
		} else if m.Type == "begin" {
			setTrayStatus("PhoneMIC · 正在听写")
		} else if m.Final || m.Type == "cancel" {
			setTrayStatus("PhoneMIC · 手机已连接")
		}
		conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
		if err = conn.WriteJSON(result); err != nil {
			return
		}
	}
}

func (s *receiver) api() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/identity", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, 200, s.identity()) })
	mux.HandleFunc("/pair", s.pair)
	mux.HandleFunc("/ws", s.socket)
	return mux
}

func (s *receiver) control() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; frame-ancestors 'none'")
		if r.Host != fmt.Sprintf("127.0.0.1:%d", controlPort) {
			http.Error(w, "Loopback only", 403)
			return
		}
		if equal(r.URL.Query().Get("key"), s.uiSecret) {
			http.SetCookie(w, &http.Cookie{Name: "phonemic", Value: s.uiSecret, HttpOnly: true, SameSite: http.SameSiteStrictMode, Path: "/"})
			http.Redirect(w, r, "/", http.StatusSeeOther)
			return
		}
		cookie, err := r.Cookie("phonemic")
		if err != nil || !equal(cookie.Value, s.uiSecret) {
			http.Error(w, "Open PhoneMIC from the tray icon", 403)
			return
		}
		if r.Method == http.MethodPost && r.Header.Get("X-PhoneMIC") != "1" {
			http.Error(w, "Invalid request", 403)
			return
		}
		switch {
		case r.URL.Path == "/" && r.Method == http.MethodGet:
			writeJSON(w, 200, map[string]string{"service": "phonemic-test-control"})
		case r.URL.Path == "/status" && r.Method == http.MethodGet:
			s.mu.Lock()
			defer s.mu.Unlock()
			clients := make([]pairedClient, 0, len(s.config.Clients))
			for _, c := range s.config.Clients {
				clients = append(clients, c)
			}
			info := s.identity()
			info["connected"] = s.active
			info["addresses"], info["clients"], info["startup"] = localAddresses(), clients, s.config.Startup
			writeJSON(w, 200, info)

		case r.URL.Path == "/revoke" && r.Method == http.MethodPost:
			s.mu.Lock()
			previous := s.config.Clients
			s.config.Clients = make(map[string]pairedClient)
			if err := s.saveLocked(); err != nil {
				s.config.Clients = previous
				s.mu.Unlock()
				writeJSON(w, 500, map[string]string{"error": err.Error()})
				return
			}
			for _, conn := range s.clients {
				conn.Close()
			}
			s.mu.Unlock()
			writeJSON(w, 200, map[string]bool{"ok": true})
		case r.URL.Path == "/startup" && r.Method == http.MethodPost:
			var body struct{ Enabled bool }
			if json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024)).Decode(&body) != nil {
				http.Error(w, "Invalid body", 400)
				return
			}
			if err := setStartup(body.Enabled); err != nil {
				writeJSON(w, 500, map[string]string{"error": err.Error()})
				return
			}
			s.mu.Lock()
			s.config.Startup = body.Enabled
			s.config.StartupConfigured = true
			err := s.saveLocked()
			s.mu.Unlock()
			if err != nil {
				writeJSON(w, 500, map[string]string{"error": err.Error()})
				return
			}
			writeJSON(w, 200, map[string]bool{"ok": true})
		case r.URL.Path == "/quit" && r.Method == http.MethodPost:
			writeJSON(w, 200, map[string]bool{"ok": true})
			go func() { time.Sleep(200 * time.Millisecond); close(s.quit) }()
		default:
			http.NotFound(w, r)
		}
	})
}

func localAddresses() []string {
	var result []string
	addresses, _ := net.InterfaceAddrs()
	for _, address := range addresses {
		if ip, ok := address.(*net.IPNet); ok && !ip.IP.IsLoopback() && !ip.IP.IsLinkLocalUnicast() && ip.IP.To4() != nil {
			if v4 := ip.IP.To4(); v4[0] == 198 && (v4[1] == 18 || v4[1] == 19) {
				continue
			}
			result = append(result, ip.IP.String())
		}
	}
	rank := func(address string) int {
		if strings.HasPrefix(address, "192.168.") {
			return 0
		}
		if strings.HasPrefix(address, "10.") {
			return 1
		}
		if net.ParseIP(address).IsPrivate() {
			return 2
		}
		return 3
	}
	sort.SliceStable(result, func(i, j int) bool { return rank(result[i]) < rank(result[j]) })
	return result
}

func (s *receiver) discover() {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{Port: discoveryPort})
	if err != nil {
		log.Print("Discovery unavailable: ", err)
		return
	}
	defer conn.Close()
	buffer := make([]byte, 1024)
	for {
		n, remote, readErr := conn.ReadFromUDP(buffer)
		if readErr != nil {
			return
		}
		var request struct{ Service, Nonce string }
		if json.Unmarshal(buffer[:n], &request) != nil || request.Service != "phonemic-discover-v1" || len(request.Nonce) < 8 || len(request.Nonce) > 64 {
			continue
		}
		info := s.identity()
		info["nonce"] = request.Nonce
		packet, _ := json.Marshal(info)
		_, _ = conn.WriteToUDP(packet, remote)
	}
}

func (s *receiver) resetLinks() {
	s.mu.Lock()
	defer s.mu.Unlock()
	previous := s.config.Clients
	s.config.Clients = make(map[string]pairedClient)
	if err := s.saveLocked(); err != nil {
		s.config.Clients = previous
		return
	}
	for _, conn := range s.clients {
		_ = conn.Close()
	}
}

func main() {
	runtime.GOMAXPROCS(1)
	debug.SetMemoryLimit(24 << 20)
	debug.SetGCPercent(75)
	_ = flag.Bool("background", true, "Run silently in the background")
	selfTest := flag.String("self-test", "", "Write native keyboard self-test results")
	controlFile := flag.String("test-control-file", "", "Enable a private integration-test control endpoint")
	verificationFile := flag.String("test-window", "", "Restrict integration tests to a scratch window")
	flag.Parse()
	if *selfTest != "" {
		if platformSelfTest(*selfTest) != nil {
			os.Exit(1)
		}
		return
	}
	dataDir, err := os.UserConfigDir()
	if err != nil {
		return
	}
	s, cert, err := loadReceiver(filepath.Join(dataDir, "PhoneMIC"), newKeyboard())
	if err != nil {
		return
	}
	listener, err := tls.Listen("tcp4", fmt.Sprintf(":%d", servicePort), &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12})
	if err != nil {
		return
	}
	defer listener.Close()
	if !s.config.StartupConfigured {
		if setStartup(true) == nil {
			s.config.Startup = true
			s.config.StartupConfigured = true
			_ = s.saveLocked()
		}
	}
	if *verificationFile != "" {
		target, err := openVerificationWindow(*verificationFile, s.quit)
		if err != nil {
			return
		}
		s.input = newEngine(restrictedKeyboard{inner: newKeyboard(), target: target})
	}
	if *controlFile != "" {
		controlListener, err := net.Listen("tcp4", fmt.Sprintf("127.0.0.1:%d", controlPort))
		if err != nil {
			return
		}
		defer controlListener.Close()
		data := s.identity()
		data["controlUrl"] = fmt.Sprintf("http://127.0.0.1:%d/?key=%s", controlPort, s.uiSecret)
		data["addresses"] = localAddresses()
		encoded, _ := json.Marshal(data)
		if os.WriteFile(*controlFile, encoded, 0600) != nil {
			return
		}
		go func() {
			_ = (&http.Server{Handler: s.control(), ReadHeaderTimeout: 5 * time.Second}).Serve(controlListener)
		}()
	}
	go func() {
		_ = (&http.Server{Handler: s.api(), ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 45 * time.Second, MaxHeaderBytes: 16384}).Serve(listener)
	}()
	go s.discover()
	runTray(prepareTrayIcon(s.dir), s.resetLinks, s.quit)
}
