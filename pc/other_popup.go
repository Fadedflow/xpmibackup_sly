//go:build !windows

// 非 Windows 平台无原生弹窗：仅记录日志，由控制页的「连接请求」卡片确认。
package main

func pairPopup(device string, decide func(bool)) {
	logf("手机「%s」请求连接：请在控制页「连接请求」卡片中确认", device)
	_ = decide
}
