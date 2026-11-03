//go:build !windows

package main

import "errors"

func setTrayStatus(string) {}

type unsupportedKeyboard struct{}

func newKeyboard() keyboard                                              { return unsupportedKeyboard{} }
func (unsupportedKeyboard) Capture() (inputTarget, error)                { return inputTarget{}, errUnsupported }
func (unsupportedKeyboard) Apply(inputTarget, string, string) error      { return errUnsupported }
func (unsupportedKeyboard) Shortcut(inputTarget, keyboardShortcut) error { return errUnsupported }
func setStartup(bool) error                                              { return errUnsupported }
func openBrowser(string)                                                 {}
func showError(text string)                                              { panic(text) }
func runTray(_ string, _ func(), quit chan struct{})                     { <-quit }
func prepareTrayIcon(string) string                                      { return "" }
func platformSelfTest(string) error                                      { return errors.New("Windows required") }
func openVerificationWindow(string, chan struct{}) (inputTarget, error) {
	return inputTarget{}, errUnsupported
}
