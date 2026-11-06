package main

import (
	"fmt"
	"strings"
)

const firewallRulePrefix = "PhoneMIC-LAN-v2-"

func psLiteral(value string) string { return "'" + strings.ReplaceAll(value, "'", "''") + "'" }

func firewallScript(program string, configure bool) string {
	script := "$ErrorActionPreference='Stop'; $program=" + psLiteral(program) + "; "
	if configure {
		// Only remove local, app-specific blocks created when a user denied the Windows prompt.
		script += `Get-NetFirewallApplicationFilter -PolicyStore PersistentStore -Program $program -ErrorAction SilentlyContinue | Get-NetFirewallRule | Where-Object { $_.Direction -eq 'Inbound' -and $_.Action -eq 'Block' } | Remove-NetFirewallRule; `
	}
	for _, entry := range []struct{ name, protocol, ports string }{{"TCP", "TCP", "8765"}, {"UDP", "UDP", "45873,5353"}} {
		name := psLiteral(firewallRulePrefix + entry.name)
		if configure {
			script += "Get-NetFirewallRule -PolicyStore PersistentStore -Name " + name + " -ErrorAction SilentlyContinue | Remove-NetFirewallRule; "
			// Include routed private networks and common VPN address space, in all Windows profiles.
			script += fmt.Sprintf("New-NetFirewallRule -Name %s -DisplayName %s -Group 'PhoneMIC LAN' -Direction Inbound -Action Allow -Enabled True -Profile Any -Program $program -Protocol %s -LocalPort %s -RemoteAddress LocalSubnet,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,100.64.0.0/10,fc00::/7,fe80::/10 | Out-Null; ", name, name, entry.protocol, entry.ports)
		} else {
			script += "$rule=Get-NetFirewallRule -PolicyStore ActiveStore -Name " + name + " -ErrorAction SilentlyContinue; " +
				"if (!$rule -or $rule.Enabled -ne 'True' -or $rule.Action -ne 'Allow' -or $rule.Profile -ne 'Any' -or ($rule | Get-NetFirewallApplicationFilter).Program -ne $program) { exit 1 }; "
		}
	}
	return script
}
