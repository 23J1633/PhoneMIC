package main

import (
	"testing"
	"time"
)

func TestFirewallScriptsParseInWindowsPowerShell(t *testing.T) {
	for _, configure := range []bool{false, true} {
		script := firewallScript(`C:\Users\O'Brien\PhoneMIC Desktop.exe`, configure)
		check := "$tokens=$null; $parseErrors=$null; [System.Management.Automation.Language.Parser]::ParseInput(" + psLiteral(script) + ", [ref]$tokens, [ref]$parseErrors) | Out-Null; if ($parseErrors.Count -ne 0) { throw ($parseErrors | Out-String) }"
		if err := powershell(check, 20*time.Second); err != nil {
			t.Fatalf("configure=%t: %v", configure, err)
		}
	}
}
