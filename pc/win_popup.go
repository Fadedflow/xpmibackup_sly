//go:build windows

// mibackpc Windows 原生配对弹窗：纯 syscall（user32.MessageBoxW），零 CGO。
package main

import (
	"fmt"
	"syscall"
	"unsafe"
)

var (
	user32          = syscall.NewLazyDLL("user32.dll")
	procMessageBoxW = user32.NewProc("MessageBoxW")
)

const (
	mbYesno         = 0x04
	mbIconQuestion  = 0x20
	mbTopmost       = 0x40000
	mbSetForeground = 0x10000
	mbSystemModal   = 0x1000
	idYes           = 6
)

// pairPopup 弹出置顶的 Windows 原生确认框（是/否），用户选择后在回调里落定。
// 弹窗本身阻塞在独立 goroutine，不干扰 HTTP 服务；若该请求已被新请求顶替，
// pairDecide 会按 ID 校验自动作废这次选择。
func pairPopup(device string, decide func(bool)) {
	go func() {
		text, err1 := syscall.UTF16PtrFromString(fmt.Sprintf(
			"手机「%s」请求连接到本机的备份接收器。\n\n允许该设备备份到这台电脑吗？", device))
		title, err2 := syscall.UTF16PtrFromString("mibackpc · 连接请求")
		if err1 != nil || err2 != nil {
			decide(false)
			return
		}
		ret, _, _ := procMessageBoxW.Call(
			0,
			uintptr(unsafe.Pointer(text)),
			uintptr(unsafe.Pointer(title)),
			uintptr(mbYesno|mbIconQuestion|mbTopmost|mbSetForeground|mbSystemModal),
		)
		decide(ret == idYes)
	}()
}
