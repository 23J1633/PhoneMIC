package main

import (
	"crypto/tls"
	"fmt"
	"log"
	"net"
	"os"
	"sort"
	"strings"
	"time"

	"github.com/grandcat/zeroconf"
)

func listenLAN(port int, config *tls.Config) ([]net.Listener, error) {
	// Acquire IPv4 first. On Windows, an IPv6 wildcard may otherwise coexist with
	// an older IPv4 receiver and silently send the phone to the wrong process.
	v4, err := tls.Listen("tcp4", fmt.Sprintf(":%d", port), config)
	if err != nil {
		return nil, err
	}
	listeners := []net.Listener{v4}
	actualPort := v4.Addr().(*net.TCPAddr).Port
	v6, err := tls.Listen("tcp6", fmt.Sprintf("[::]:%d", actualPort), config)
	if err == nil {
		listeners = append(listeners, v6)
	} else {
		log.Print("IPv6 unavailable; IPv4 remains active: ", err)
	}
	return listeners, nil
}

func usableAddress(ip net.IP) bool {
	if ip == nil || ip.IsLoopback() || ip.IsUnspecified() || ip.IsLinkLocalUnicast() || ip.IsMulticast() {
		return false
	}
	if v4 := ip.To4(); v4 != nil && v4[0] == 198 && (v4[1] == 18 || v4[1] == 19) {
		return false // VPN fake-IP range is never a receiver address.
	}
	return true
}

func lanInterfaces() []net.Interface {
	interfaces, _ := net.Interfaces()
	var result []net.Interface
	for _, adapter := range interfaces {
		if adapter.Flags&net.FlagUp == 0 || adapter.Flags&net.FlagLoopback != 0 || adapter.Flags&net.FlagPointToPoint != 0 {
			continue
		}
		// Avoid publishing hypervisor and VPN interfaces as physical LAN endpoints.
		name := strings.ToLower(adapter.Name)
		virtual := false
		for _, prefix := range []string{"vethernet", "vmware", "virtualbox", "tailscale", "tun", "tap", "utun", "wg", "clash", "mihomo", "meta", "wintun"} {
			if strings.HasPrefix(name, prefix) {
				virtual = true
				break
			}
		}
		addresses, _ := adapter.Addrs()
		hasAddress := false
		for _, address := range addresses {
			if ip, ok := address.(*net.IPNet); ok && usableAddress(ip.IP) {
				hasAddress = true
				break
			}
		}
		if !virtual && hasAddress {
			result = append(result, adapter)
		}
	}
	return result
}

func localAddresses() []string {
	var result []string
	seen := make(map[string]bool)
	add := func(address net.Addr) {
		ip, ok := address.(*net.IPNet)
		if ok && usableAddress(ip.IP) && !seen[ip.IP.String()] {
			seen[ip.IP.String()] = true
			result = append(result, ip.IP.String())
		}
	}
	for _, adapter := range lanInterfaces() {
		addresses, _ := adapter.Addrs()
		for _, address := range addresses {
			add(address)
		}
	}
	physical := len(result)
	addresses, _ := net.InterfaceAddrs()
	for _, address := range addresses {
		add(address)
	}
	rank := func(value string) int {
		ip := net.ParseIP(value)
		if ip.To4() != nil && ip.IsPrivate() {
			return 0
		}
		if ip.To4() != nil {
			return 1
		}
		return 2
	}
	sort.SliceStable(result[:physical], func(i, j int) bool { return rank(result[i]) < rank(result[j]) })
	return result
}

func (s *receiver) advertise() {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	var servers []*zeroconf.Server
	shutdown := func() {
		for _, server := range servers {
			server.Shutdown()
		}
		servers = nil
	}
	defer shutdown()
	previous := ""
	for {
		interfaces := lanInterfaces()
		var signature []string
		for _, adapter := range interfaces {
			addresses, _ := adapter.Addrs()
			for _, address := range addresses {
				if ip, ok := address.(*net.IPNet); ok && usableAddress(ip.IP) {
					signature = append(signature, fmt.Sprintf("%d:%s", adapter.Index, ip.IP))
				}
			}
		}
		sort.Strings(signature)
		next := strings.Join(signature, "|")
		if next != previous || (len(servers) == 0 && next != "") {
			shutdown()
			previous = next
			if next != "" {
				name, _ := os.Hostname()
				for _, adapter := range interfaces {
					var ips []string
					addresses, _ := adapter.Addrs()
					for _, address := range addresses {
						if ip, ok := address.(*net.IPNet); ok && usableAddress(ip.IP) {
							ips = append(ips, ip.IP.String())
						}
					}
					instance := fmt.Sprintf("PhoneMIC-%s-%d", s.config.ID, adapter.Index)
					// Publish only this interface's addresses; another adapter may be on an unreachable subnet.
					server, err := zeroconf.RegisterProxy(instance, "_phonemic._tcp", "local.", servicePort, instance, ips,
						[]string{"id=" + s.config.ID, "name=" + name, "fingerprint=" + s.fingerprint, "version=1"}, []net.Interface{adapter})
					if err != nil {
						log.Print("DNS-SD unavailable on ", adapter.Name, ": ", err)
					} else {
						servers = append(servers, server)
					}
				}
			}
		}
		select {
		case <-s.quit:
			return
		case <-ticker.C:
		}
	}
}
