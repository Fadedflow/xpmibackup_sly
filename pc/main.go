// mibackpc —— XpMiBackup 电脑端接收器
//
// 单文件、纯标准库、零 CGO：把任意文件夹变成一台 WebDAV 服务器，
// 供手机端 XpMiBackup 备份/恢复到电脑。
//
// 连接流程（手机端无需手动配置）：
//
//	发现：手机备份页自动探测 —— 局域网走 UDP 8322 广播应答，
//	      USB 走 adb reverse 后探测 http://127.0.0.1:8321/miback/info
//	配对：手机 POST /pair/requests → 本机弹 Windows 原生确认框
//	      （或控制页「连接请求」卡片）→ 同意后凭据单次下发
//	传输：http://<电脑局域网IP>:8321/dav/（USB 为 http://127.0.0.1:8321/dav/）
//
// 协议子集按 src/app/.../comm/WebdavFileHelp.java 的线上行为逐条对齐：
//
//	PROPFIND(Depth 0/1) 必须返回 207，首条 href 为目录自身（客户端跳过），
//	href 需 URL 转义；getcontentlength/collection 节点供客户端正则解析；
//	MKCOL/PUT/GET/DELETE 为基础语义；客户端强制 HTTP/1.1 + Basic Auth。
package main

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ---------- 配置持久化 ----------

type Config struct {
	Root string   `json:"root"` // 备份文件落地目录
	Port int      `json:"port"`
	User string   `json:"user"`
	Pass string   `json:"pass"`
	Pair []string `json:"paired"` // 已配对过的手机设备名（展示用，最新在后，最多保留 8 台）
}

func configPath() string {
	exe, err := os.Executable()
	if err != nil {
		return "mibackpc.json"
	}
	return filepath.Join(filepath.Dir(exe), "mibackpc.json")
}

func loadConfig() *Config {
	c := &Config{Port: 8321, User: "miback"}
	if b, err := os.ReadFile(configPath()); err == nil {
		_ = json.Unmarshal(b, c)
	}
	if c.Port <= 0 || c.Port > 65535 {
		c.Port = 8321
	}
	if c.User == "" {
		c.User = "miback"
	}
	if c.Pass == "" {
		b := make([]byte, 8)
		_, _ = rand.Read(b)
		c.Pass = hex.EncodeToString(b)
	}
	return c
}

func (c *Config) save() {
	b, _ := json.MarshalIndent(c, "", "  ")
	_ = os.WriteFile(configPath(), b, 0600)
}

// recordPairedDevice 记录已配对手机（去重，最多 8 台，供控制页展示）
func (c *Config) recordPairedDevice(name string) {
	if name == "" {
		return
	}
	for _, p := range c.Pair {
		if p == name {
			return
		}
	}
	c.Pair = append(c.Pair, name)
	if len(c.Pair) > 8 {
		c.Pair = c.Pair[len(c.Pair)-8:]
	}
	c.save()
}

// ---------- 传输日志（环形） ----------

const logMax = 300

var (
	logMu  sync.Mutex
	logBuf []string
)

func logf(format string, a ...any) {
	line := time.Now().Format("15:04:05 ") + fmt.Sprintf(format, a...)
	logMu.Lock()
	logBuf = append(logBuf, line)
	if len(logBuf) > logMax {
		logBuf = logBuf[len(logBuf)-logMax:]
	}
	logMu.Unlock()
	fmt.Println(line)
}

// ---------- WebDAV 子集 ----------

var davFS struct {
	mu   sync.RWMutex
	root string // 绝对路径；空串表示未设置
}

func davRoot() string {
	davFS.mu.RLock()
	defer davFS.mu.RUnlock()
	return davFS.root
}

// safeJoin 把 WebDAV 路径映射到本地文件系统，拒绝越界（.. / 绝对路径 / 盘符）
func safeJoin(rel string) (string, error) {
	rel = strings.ReplaceAll(rel, "\\", "/")
	rel = strings.TrimPrefix(rel, "/")
	clean := path.Clean("/" + rel) // 前导 / 使 .. 无法越过根
	clean = strings.TrimPrefix(clean, "/")
	if clean == "." || clean == "" {
		return davRoot(), nil
	}
	for _, seg := range strings.Split(clean, "/") {
		if seg == ".." || seg == "." || seg == "" {
			return "", fmt.Errorf("bad path")
		}
		if strings.HasSuffix(seg, ":") { // 盘符
			return "", fmt.Errorf("bad path")
		}
	}
	return filepath.Join(davRoot(), filepath.FromSlash(clean)), nil
}

// hrefEscape 生成 href：URL 路径转义（空格→%20 等）+ XML 转义（& → &amp;）
func hrefEscape(rel string) string {
	parts := strings.Split(strings.ReplaceAll(rel, "\\", "/"), "/")
	for i, p := range parts {
		parts[i] = url.PathEscape(p)
	}
	h := "/dav/" + strings.TrimPrefix(path.Clean("/"+strings.Join(parts, "/")), "/")
	h = strings.ReplaceAll(h, "&", "&amp;")
	h = strings.ReplaceAll(h, "<", "&lt;")
	h = strings.ReplaceAll(h, ">", "&gt;")
	return h
}

func xmlEscape(s string) string {
	s = strings.ReplaceAll(s, "&", "&amp;")
	s = strings.ReplaceAll(s, "<", "&lt;")
	s = strings.ReplaceAll(s, ">", "&gt;")
	return s
}

// propfindXML 生成 Multi-Status。首条恒为目录自身（客户端无条件跳过首条）。
// 仅输出客户端正则实际解析的三类节点：href / getcontentlength / collection。
func propfindXML(rel string, depth string) string {
	var b strings.Builder
	b.WriteString(`<?xml version="1.0" encoding="utf-8"?>` + "\n")
	b.WriteString(`<D:multistatus xmlns:D="DAV:">` + "\n")

	writeEntry := func(relPath string, isDir bool, size int64) {
		b.WriteString("<D:response>\n")
		fmt.Fprintf(&b, "<D:href>%s</D:href>\n", hrefEscape(relPath))
		b.WriteString("<D:propstat><D:prop>\n")
		if isDir {
			b.WriteString("<D:resourcetype><D:collection/></D:resourcetype>\n")
		} else {
			b.WriteString("<D:resourcetype/>\n")
			fmt.Fprintf(&b, "<D:getcontentlength>%d</D:getcontentlength>\n", size)
		}
		b.WriteString("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>\n")
		b.WriteString("</D:response>\n")
	}

	writeEntry(rel, true, 0) // 目录自身

	if depth != "0" {
		if local, err := safeJoin(rel); err == nil {
			if entries, err2 := os.ReadDir(local); err2 == nil {
				sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
				base := strings.TrimSuffix("/"+strings.Trim(rel, "/"), "/")
				for _, e := range entries {
					child := base + "/" + e.Name()
					if e.IsDir() {
						writeEntry(child, true, 0)
					} else {
						var size int64
						if info, err := e.Info(); err == nil {
							size = info.Size()
						}
						writeEntry(child, false, size)
					}
				}
			}
		}
	}

	b.WriteString("</D:multistatus>")
	return b.String()
}

func davAuth(w http.ResponseWriter, r *http.Request, c *Config) bool {
	user, pass, ok := r.BasicAuth()
	if !ok ||
		subtle.ConstantTimeCompare([]byte(user), []byte(c.User)) != 1 ||
		subtle.ConstantTimeCompare([]byte(pass), []byte(c.Pass)) != 1 {
		w.Header().Set("WWW-Authenticate", `Basic realm="mibackpc", charset="UTF-8"`)
		http.Error(w, "401 Unauthorized", http.StatusUnauthorized)
		return false
	}
	return true
}

// 记录传输字节数与速度
type countingWriter struct {
	http.ResponseWriter
	n int64
}

func (cw *countingWriter) Write(p []byte) (int, error) {
	n, err := cw.ResponseWriter.Write(p)
	cw.n += int64(n)
	return n, err
}

func speedOf(n int64, d time.Duration) string {
	if d <= 0 {
		d = time.Millisecond
	}
	mbps := float64(n) / d.Seconds() / 1024 / 1024
	if n < 1024*1024 {
		return fmt.Sprintf("%.0f KB/s", float64(n)/d.Seconds()/1024)
	}
	return fmt.Sprintf("%.1f MB/s", mbps)
}

func human(n int64) string {
	const kb, mb, gb = 1024, 1024 * 1024, 1024 * 1024 * 1024
	switch {
	case n >= gb:
		return fmt.Sprintf("%.2f GB", float64(n)/gb)
	case n >= mb:
		return fmt.Sprintf("%.1f MB", float64(n)/mb)
	case n >= kb:
		return fmt.Sprintf("%.1f KB", float64(n)/kb)
	default:
		return fmt.Sprintf("%d B", n)
	}
}

// davHandler 实现 PROPFIND / MKCOL / PUT / GET / HEAD / DELETE / OPTIONS
func davHandler(w http.ResponseWriter, r *http.Request, c *Config) {
	if !davAuth(w, r, c) {
		return
	}
	if davRoot() == "" {
		http.Error(w, "backup root not set", http.StatusPreconditionFailed)
		return
	}

	rel := strings.TrimPrefix(r.URL.Path, "/dav")
	rel = strings.TrimSuffix(rel, "/")
	if rel == "" {
		rel = "/"
	}

	start := time.Now()
	switch r.Method {
	case "OPTIONS":
		w.Header().Set("DAV", "1, 2")
		w.Header().Set("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, MKCOL")
		w.WriteHeader(http.StatusOK)

	case "PROPFIND":
		if _, err := safeJoin(rel); err != nil {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		local, err := safeJoin(rel)
		if err != nil || !dirExists(local) {
			http.Error(w, "404 Not Found", http.StatusNotFound)
			return
		}
		depth := r.Header.Get("Depth")
		if depth == "" {
			depth = "1"
		}
		w.Header().Set("Content-Type", `application/xml; charset=utf-8`)
		w.WriteHeader(http.StatusMultiStatus) // 客户端 testConnection 以 207 判定连通
		_, _ = io.WriteString(w, propfindXML(rel, depth))

	case "MKCOL":
		local, err := safeJoin(rel)
		if err != nil {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		if pathExists(local) {
			w.WriteHeader(http.StatusMethodNotAllowed) // 客户端 mkdir 逐级吞掉该错误
			return
		}
		if err := os.MkdirAll(local, 0o755); err != nil {
			http.Error(w, "409 Conflict", http.StatusConflict)
			return
		}
		logf("MKCOL %s", rel)
		w.WriteHeader(http.StatusCreated)

	case "PUT":
		local, err := safeJoin(rel)
		if err != nil || strings.HasSuffix(rel, "/") {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		if err := os.MkdirAll(filepath.Dir(local), 0o755); err != nil {
			http.Error(w, "409 Conflict", http.StatusConflict)
			return
		}
		// 临时文件 + rename：断连/中途失败不落半截文件（分片重试才不会把坏分片当有效数据）
		tmp := local + ".mibackpart"
		f, err := os.Create(tmp)
		if err != nil {
			http.Error(w, "403 Forbidden", http.StatusForbidden)
			return
		}
		n, copyErr := io.Copy(f, r.Body)
		closeErr := f.Close()
		if copyErr == nil {
			copyErr = closeErr
		}
		if copyErr != nil {
			_ = os.Remove(tmp)
			logf("PUT  FAIL %s (%s)", rel, copyErr)
			http.Error(w, "500 write failed", http.StatusInternalServerError)
			return
		}
		if err := os.Rename(tmp, local); err != nil {
			_ = os.Remove(tmp)
			logf("PUT  FAIL %s (%s)", rel, err)
			http.Error(w, "500 rename failed", http.StatusInternalServerError)
			return
		}
		logf("PUT  OK   %s  %s  %s", rel, human(n), speedOf(n, time.Since(start)))
		w.WriteHeader(http.StatusCreated)

	case "GET", "HEAD":
		local, err := safeJoin(rel)
		if err != nil || dirExists(local) {
			http.Error(w, "404 Not Found", http.StatusNotFound)
			return
		}
		f, err := os.Open(local)
		if err != nil {
			http.Error(w, "404 Not Found", http.StatusNotFound)
			return
		}
		defer f.Close()
		st, err := f.Stat()
		if err != nil {
			http.Error(w, "404 Not Found", http.StatusNotFound)
			return
		}
		cw := &countingWriter{ResponseWriter: w}
		http.ServeContent(cw, r, st.Name(), st.ModTime(), f)
		if r.Method == "GET" {
			logf("GET  OK   %s  %s  %s", rel, human(cw.n), speedOf(cw.n, time.Since(start)))
		}

	case "DELETE":
		local, err := safeJoin(rel)
		if err != nil || local == davRoot() {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		if !pathExists(local) {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		if err := os.RemoveAll(local); err != nil { // RFC4918：DELETE 天然递归
			http.Error(w, "500 delete failed", http.StatusInternalServerError)
			return
		}
		logf("DEL  %s", rel)
		w.WriteHeader(http.StatusNoContent)

	default:
		w.Header().Set("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, MKCOL")
		http.Error(w, "405 Method Not Allowed", http.StatusMethodNotAllowed)
	}
}

func pathExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

func dirExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && st.IsDir()
}

// ---------- 自动发现（UDP 广播应答） ----------

const (
	discoverPort  = 8322
	discoverMagic = "MIBACKPC_DISCOVER_V1"
)

func hostName() string {
	if h, err := os.Hostname(); err == nil && h != "" {
		return h
	}
	return "PC"
}

func discoverPayload(c *Config) []byte {
	b, _ := json.Marshal(map[string]any{
		"service": "mibackpc",
		"name":    hostName(),
		"tcp":     c.Port,
		"ver":     1,
	})
	return b
}

// serveDiscovery 监听 UDP 8322：手机端备份页发广播探测码，本机单播回 JSON。
// 手机只需发广播 + 收单播应答，无需组播锁等特殊权限。
func serveDiscovery(c *Config) {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{Port: discoverPort})
	if err != nil {
		logf("自动发现(UDP %d)不可用: %v", discoverPort, err)
		return
	}
	defer conn.Close()
	logf("自动发现已就绪（UDP %d）", discoverPort)
	buf := make([]byte, 128)
	for {
		n, raddr, err := conn.ReadFromUDP(buf)
		if err != nil {
			return
		}
		if strings.TrimSpace(string(buf[:n])) != discoverMagic {
			continue
		}
		_, _ = conn.WriteToUDP(discoverPayload(c), raddr)
	}
}

// ---------- 手机配对（请求 → 电脑端确认 → 下发凭据） ----------

const pairTTL = 5 * time.Minute

type pairReq struct {
	ID      string    `json:"id"`
	Device  string    `json:"device"`
	IP      string    `json:"ip"`
	Created time.Time `json:"created"`
	Status  string    `json:"status"` // pending / approved / denied
}

var pairStore struct {
	mu      sync.Mutex
	current *pairReq // 同时只保留一个请求；新请求顶替旧的（旧弹窗选择按 ID 作废）
}

// pairDecide 电脑端落定结果。仅当 id 仍是当前 pending 时生效；approved 保留在
// current 等手机取件，denied 保留供结果端点返回后清掉。
func pairDecide(c *Config, id string, approve bool) bool {
	pairStore.mu.Lock()
	defer pairStore.mu.Unlock()
	req := pairStore.current
	if req == nil || req.ID != id || req.Status != "pending" {
		return false
	}
	if approve {
		req.Status = "approved"
		c.recordPairedDevice(req.Device)
		logf("已允许手机「%s」（%s）连接", req.Device, req.IP)
	} else {
		req.Status = "denied"
		logf("已拒绝手机「%s」（%s）连接", req.Device, req.IP)
	}
	return true
}

func pairView(req *pairReq) map[string]any {
	if req == nil || req.Status != "pending" {
		return nil
	}
	return map[string]any{
		"device": req.Device,
		"ip":     req.IP,
		"age":    int(time.Since(req.Created).Seconds()),
	}
}

// pairHandler 面向局域网手机：POST /pair/requests 发起请求，GET …/result 轮询结果。
// 结果仅下发给发起请求的同一 IP，凭据取走即焚。
func pairHandler(w http.ResponseWriter, r *http.Request, c *Config) {
	callerIP, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil || net.ParseIP(callerIP) == nil {
		http.Error(w, "bad client addr", http.StatusBadRequest)
		return
	}

	switch {
	case r.URL.Path == "/pair/requests" && r.Method == "POST":
		var req struct {
			Device string `json:"device"`
			ReqID  string `json:"reqId"`
		}
		_ = json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&req)
		req.Device = strings.TrimSpace(req.Device)
		req.ReqID = strings.TrimSpace(req.ReqID)
		if req.Device == "" || len([]rune(req.Device)) > 64 || len(req.ReqID) < 16 || len(req.ReqID) > 64 {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		pending := &pairReq{
			ID:      req.ReqID,
			Device:  req.Device,
			IP:      callerIP,
			Created: time.Now(),
			Status:  "pending",
		}
		pairStore.mu.Lock()
		pairStore.current = pending
		pairStore.mu.Unlock()
		logf("收到手机「%s」连接请求（%s）", pending.Device, callerIP)
		pairPopup(pending.Device, func(ok bool) { pairDecide(c, pending.ID, ok) })
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]any{"status": "pending"})

	case strings.HasPrefix(r.URL.Path, "/pair/requests/") && strings.HasSuffix(r.URL.Path, "/result") && r.Method == "GET":
		id := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/pair/requests/"), "/result")
		pairStore.mu.Lock()
		req := pairStore.current
		var resp map[string]any
		switch {
		case req == nil || req.ID != id:
			resp = map[string]any{"status": "expired"}
		case time.Since(req.Created) > pairTTL:
			resp = map[string]any{"status": "expired"}
			pairStore.current = nil
		case req.IP != callerIP:
			resp = map[string]any{"status": "forbidden"}
		case req.Status == "pending":
			resp = map[string]any{"status": "pending"}
		case req.Status == "approved":
			resp = map[string]any{
				"status": "approved",
				"name":   hostName(),
				"user":   c.User,
				"pass":   c.Pass,
				"port":   c.Port,
			}
			pairStore.current = nil // 凭据取走即焚
		default: // denied
			resp = map[string]any{"status": "denied"}
			pairStore.current = nil
		}
		pairStore.mu.Unlock()
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(resp)

	default:
		http.NotFound(w, r)
	}
}

// ---------- 控制面（仅本机访问） ----------

func lanIPs() []string {
	var out []string
	ifaces, err := net.Interfaces()
	if err != nil {
		return out
	}
	for _, ia := range ifaces {
		if ia.Flags&net.FlagUp == 0 || ia.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := ia.Addrs()
		for _, a := range addrs {
			ipnet, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			ip4 := ipnet.IP.To4()
			if ip4 == nil || !ip4.IsPrivate() {
				continue
			}
			out = append(out, ip4.String())
		}
	}
	sort.Strings(out)
	return out
}

func isLoopback(r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return false
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func controlHandler(w http.ResponseWriter, r *http.Request, c *Config) {
	// 控制面只允许本机：局域网设备只应访问 /dav/*
	if !isLoopback(r) {
		http.Error(w, "control interface is local-only", http.StatusForbidden)
		return
	}

	switch {
	case r.URL.Path == "/api/state" && r.Method == "GET":
		pairStore.mu.Lock()
		pending := pairView(pairStore.current)
		pairStore.mu.Unlock()
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"root":          davRoot(),
			"port":          c.Port,
			"user":          c.User,
			"pass":          c.Pass,
			"lanIPs":        lanIPs(),
			"adbURL":        fmt.Sprintf("http://127.0.0.1:%d/dav/", c.Port),
			"adbOK":         adbReverseCached(c.Port),
			"name":          hostName(),
			"pairPending":   pending,
			"pairedDevices": c.Pair,
			"discoverPort":  discoverPort,
		})

	case r.URL.Path == "/api/pair/decide" && r.Method == "POST":
		var req struct {
			Approve bool `json:"approve"`
		}
		_ = json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&req)
		pairStore.mu.Lock()
		var id string
		if pairStore.current != nil {
			id = pairStore.current.ID
		}
		pairStore.mu.Unlock()
		ok := id != "" && pairDecide(c, id, req.Approve)
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": ok})

	case r.URL.Path == "/api/root" && r.Method == "POST":
		var req struct {
			Root string `json:"root"`
		}
		_ = json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&req)
		req.Root = strings.TrimSpace(req.Root)
		if req.Root == "" {
			http.Error(w, "empty root", http.StatusBadRequest)
			return
		}
		abs, err := filepath.Abs(req.Root)
		if err != nil {
			http.Error(w, "bad root", http.StatusBadRequest)
			return
		}
		if err := os.MkdirAll(abs, 0o755); err != nil {
			http.Error(w, "cannot create dir: "+err.Error(), http.StatusBadRequest)
			return
		}
		davFS.mu.Lock()
		davFS.root = abs
		davFS.mu.Unlock()
		c.Root = abs
		c.save()
		logf("备份目录设置为 %s", abs)
		w.WriteHeader(http.StatusNoContent)

	case r.URL.Path == "/api/cred" && r.Method == "POST":
		var req struct {
			User string `json:"user"`
			Pass string `json:"pass"`
		}
		_ = json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&req)
		req.User = strings.TrimSpace(req.User)
		if req.User == "" || len(req.Pass) < 4 {
			http.Error(w, "user 不能为空，pass 至少 4 位", http.StatusBadRequest)
			return
		}
		c.User, c.Pass = req.User, req.Pass
		c.save()
		logf("WebDAV 账号已更新为 %s", req.User)
		w.WriteHeader(http.StatusNoContent)

	case r.URL.Path == "/api/log" && r.Method == "GET":
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		logMu.Lock()
		defer logMu.Unlock()
		cp := make([]string, len(logBuf))
		copy(cp, logBuf)
		_ = json.NewEncoder(w).Encode(map[string]any{"lines": cp})

	case r.URL.Path == "/api/adb" && r.Method == "POST":
		ok := adbForce(c.Port)
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": ok})

	case r.URL.Path == "/" || r.URL.Path == "/index.html":
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = io.WriteString(w, indexHTML)

	default:
		http.NotFound(w, r)
	}
}

// adbReverse 尽力建立 USB 通道：手机访问 127.0.0.1:port 即到达本机（静默，仅失败记日志）
func adbReverse(port int) bool {
	for _, exe := range []string{"adb", "adb.exe"} {
		if p, err := exec.LookPath(exe); err == nil {
			cmd := exec.Command(p, "reverse", fmt.Sprintf("tcp:%d", port), fmt.Sprintf("tcp:%d", port))
			if out, err := cmd.CombinedOutput(); err == nil {
				_ = out
				return true
			} else {
				logf("adb reverse 失败: %s", strings.TrimSpace(string(out)))
			}
			return false
		}
	}
	return false
}

// adbState 缓存 USB 通道状态：控制页轮询 /api/state 很频繁，不能每次都拉起 adb 子进程刷日志
var adbState struct {
	mu      sync.Mutex
	ok      bool
	known   bool
	checked time.Time
}

// adbReverseCached 带缓存的通道探测（30s 内复用上次结果），状态变化才记日志
func adbReverseCached(port int) bool {
	adbState.mu.Lock()
	defer adbState.mu.Unlock()
	if adbState.known && time.Since(adbState.checked) < 30*time.Second {
		return adbState.ok
	}
	ok := adbReverse(port)
	if adbState.known && ok != adbState.ok {
		logf("USB 通道状态变化：%s", map[bool]string{true: "已建立", false: "未建立"}[ok])
	}
	adbState.ok, adbState.known, adbState.checked = ok, true, time.Now()
	return ok
}

// adbForce 控制页「重连」按钮：立即重新建立并刷新缓存
func adbForce(port int) bool {
	adbState.mu.Lock()
	defer adbState.mu.Unlock()
	ok := adbReverse(port)
	adbState.ok, adbState.known, adbState.checked = ok, true, time.Now()
	if ok {
		logf("USB 通道已重新建立 (%d)", port)
	}
	return ok
}

// ---------- 控制页（Apple 设计语言：系统字体栈 / 中性表面 / 单一强调色 / 明暗自适应） ----------

const indexHTML = `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>mibackpc · 电脑备份接收器</title>
<style>
 :root{
  --bg:#f5f5f7; --card:#ffffff; --text:#1d1d1f; --text2:#6e6e73;
  --hair:rgba(0,0,0,.08); --field:rgba(120,120,128,.08);
  --accent:#0071e3; --accent-press:#0060c2;
  --good:#34c759; --bad:#ff3b30; --chip:#e8e8ed;
  --shadow:0 1px 2px rgba(0,0,0,.03),0 8px 24px rgba(0,0,0,.05);
 }
 @media (prefers-color-scheme:dark){
  :root{
   --bg:#000000; --card:#1c1c1e; --text:#f5f5f7; --text2:#86868b;
   --hair:rgba(255,255,255,.12); --field:rgba(120,120,128,.22);
   --accent:#0a84ff; --accent-press:#3395ff;
   --good:#30d158; --bad:#ff453a; --chip:rgba(120,120,128,.24);
   --shadow:0 1px 2px rgba(0,0,0,.4),0 8px 24px rgba(0,0,0,.4);
  }
 }
 *{box-sizing:border-box}
 body{margin:0;background:var(--bg);color:var(--text);
  font-family:-apple-system,BlinkMacSystemFont,"SF Pro Display","SF Pro Text","PingFang SC","Segoe UI","Microsoft YaHei UI",sans-serif;
  -webkit-font-smoothing:antialiased;font-size:15px;line-height:1.47059}
 .wrap{max-width:640px;margin:0 auto;padding:48px 22px 64px}
 h1{font-size:32px;font-weight:700;letter-spacing:-.015em;margin:0}
 .sub{color:var(--text2);font-size:15px;margin:4px 0 0}
 header{display:flex;align-items:flex-end;justify-content:space-between;margin-bottom:28px}
 .badge{display:inline-flex;align-items:center;gap:6px;font-size:12px;font-weight:600;color:var(--text2);
  background:var(--chip);border-radius:980px;padding:4px 12px;white-space:nowrap}
 .dot{width:7px;height:7px;border-radius:50%;background:var(--good);flex:none}
 .card{background:var(--card);border-radius:18px;box-shadow:var(--shadow);
  padding:20px;margin-bottom:16px}
 .card h2{font-size:17px;font-weight:600;letter-spacing:-.01em;margin:0 0 4px}
 .hint{color:var(--text2);font-size:13px;margin:0 0 14px}
 .row{display:flex;gap:10px;align-items:center;margin:10px 0}
 .row>input{flex:1}
 .row>.btn{flex:none}
 .url{display:flex;align-items:center;gap:8px;margin:10px 0;
  background:var(--field);border-radius:10px;padding:10px 14px;font-size:14px}
 .url code{font-family:ui-monospace,"SF Mono",Menlo,Consolas,monospace;font-size:13px;
  word-break:break-all;flex:1;user-select:all}
 .kv{display:flex;align-items:baseline;justify-content:space-between;gap:12px;padding:10px 0}
 .kv+.kv{border-top:1px solid var(--hair)}
 .kv .k{color:var(--text2);font-size:13px;flex:none}
 .kv .v{font-size:14px;text-align:right;word-break:break-all}
 label{display:block;font-size:13px;font-weight:600;color:var(--text2);margin:14px 0 6px}
 input{width:100%;background:var(--field);border:none;outline:none;color:var(--text);
  border-radius:10px;padding:10px 14px;font-size:15px;font-family:inherit;
  transition:box-shadow .15s}
 input:focus{box-shadow:0 0 0 3.5px color-mix(in srgb,var(--accent) 35%,transparent)}
 button{font-family:inherit;cursor:pointer;border:none;border-radius:980px;
  padding:9px 18px;font-size:14px;font-weight:600;letter-spacing:-.005em;
  background:var(--accent);color:#fff;transition:background .15s,opacity .15s,transform .1s}
 button:active{opacity:.8;transform:scale(.98)}
 button.plain{background:var(--chip);color:var(--text)}
 button.small{padding:6px 13px;font-size:13px}
 button:disabled{opacity:.45;cursor:default}
 .chip{display:inline-flex;align-items:center;gap:6px;font-size:13px;font-weight:600}
 .chip .dot{width:7px;height:7px}
 .chip.no .dot{background:var(--bad)}
 .chip.ok{color:var(--text)}
 .chip.no{color:var(--text)}
 #pairCard{border:1.5px solid var(--accent)}
 #pairCard .who{font-size:15px;font-weight:600;margin:12px 0 2px}
 #pairCard .meta{color:var(--text2);font-size:13px;margin-bottom:16px}
 .pair-actions{display:flex;gap:10px}
 .pair-actions button{flex:1;padding:11px 0}
 .pair-actions button.plain{flex:0 0 96px}
 #devices:empty::after{content:"暂无已配对设备";color:var(--text2);font-size:13px}
 .device{display:inline-flex;align-items:center;gap:7px;background:var(--chip);
  border-radius:980px;padding:5px 14px;font-size:13px;margin:4px 6px 0 0}
 #log{background:var(--field);border-radius:10px;height:200px;overflow:auto;padding:12px 14px;
  font:12px/1.6 ui-monospace,"SF Mono",Menlo,Consolas,monospace;white-space:pre-wrap;color:var(--text2)}
 #toast{position:fixed;left:50%;bottom:32px;transform:translateX(-50%) translateY(20px);
  background:rgba(29,29,31,.92);color:#fff;font-size:14px;padding:10px 22px;border-radius:980px;
  opacity:0;pointer-events:none;transition:opacity .25s,transform .25s;max-width:86%}
 #toast.show{opacity:1;transform:translateX(-50%) translateY(0)}
 @media (prefers-color-scheme:dark){ #toast{background:rgba(245,245,247,.92);color:#1d1d1f} }
</style></head><body>
<div class="wrap">
 <header>
  <div><h1>mibackpc</h1><p class="sub">电脑备份接收器 · 局域网自动发现</p></div>
  <span class="badge"><span class="dot"></span>运行中</span>
 </header>

 <div class="card" id="pairCard" hidden>
  <h2>连接请求</h2>
  <p class="hint">一台手机请求备份到这台电脑，请确认是否允许。</p>
  <div class="who" id="pairWho">—</div>
  <div class="meta" id="pairMeta"></div>
  <div class="pair-actions">
   <button class="plain" onclick="decide(false)">拒绝</button>
   <button onclick="decide(true)">允许</button>
  </div>
 </div>

 <div class="card">
  <h2>连接</h2>
  <p class="hint">手机端在「备份」页自动发现本机并请求连接，无需手动填写地址。</p>
  <div id="lanURLs"></div>
  <div class="url"><code id="adbURL">…</code>
   <span class="chip" id="adbState"></span>
   <button class="small plain" onclick="doAdb()">重连</button></div>
  <p class="hint" style="margin:10px 0 0">USB 通道需要数据线连接且电脑已安装 adb；手机与电脑同一 WiFi 时走局域网。</p>
 </div>

 <div class="card">
  <h2>设置</h2>
  <label>备份落地目录</label>
  <div class="row"><input id="root"><button class="plain" onclick="setRoot()">保存</button></div>
  <label>WebDAV 账号</label>
  <div class="row"><input id="user" autocomplete="off"><input id="pass" autocomplete="off"><button class="plain" onclick="setCred()">更新</button></div>
 </div>

 <div class="card">
  <h2>已配对设备</h2>
  <p class="hint">配对成功的手机会记录在这里（本地留存，仅展示）。</p>
  <div id="devices"></div>
 </div>

 <div class="card">
  <h2>传输日志</h2>
  <div id="log"></div>
 </div>
</div>
<div id="toast"></div>
<script>
const esc = s => (s||"").replace(/&/g,"&amp;").replace(/</g,"&lt;");
let lastPair = "";
function toast(m){ const t=document.getElementById("toast"); t.textContent=m; t.classList.add("show");
  clearTimeout(t._h); t._h=setTimeout(()=>t.classList.remove("show"),2200); }
async function copy(text){ try{ await navigator.clipboard.writeText(text); toast("已拷贝"); }
  catch(e){ toast(text); } }
async function refresh(){
  const st = await (await fetch("/api/state")).json();
  document.getElementById("root").value = st.root || "";
  document.getElementById("user").value = st.user || "";
  document.getElementById("pass").value = st.pass || "";
  const ips = st.lanIPs || [];
  document.getElementById("lanURLs").innerHTML = ips.length
    ? ips.map(ip => '<div class="url"><code>http://'+esc(ip)+':'+st.port+'/dav/</code>'+
        '<button class="small plain" onclick="copy(this.previousElementSibling.textContent)">拷贝</button></div>').join("")
    : '<div class="url"><code>未检测到局域网 IP，请确认已连接 WiFi/网线</code></div>';
  document.getElementById("adbURL").textContent = st.adbURL;
  const s = document.getElementById("adbState");
  s.className = "chip " + (st.adbOK ? "ok" : "no");
  s.innerHTML = '<span class="dot"></span>' + (st.adbOK ? "已建立" : "未建立");
  const dv = document.getElementById("devices");
  dv.innerHTML = (st.pairedDevices||[]).map(d => '<span class="device"><span class="dot"></span>'+esc(d)+"</span>").join("");
  const p = st.pairPending;
  const card = document.getElementById("pairCard");
  if (p) {
    const sig = p.device + "|" + p.ip;
    card.hidden = false;
    if (sig !== lastPair) { lastPair = sig; }
    document.getElementById("pairWho").textContent = "手机 · " + p.device;
    document.getElementById("pairMeta").textContent = p.ip + " · 等待确认 " + p.age + " 秒";
  } else { card.hidden = true; lastPair = ""; }
}
async function poll(){
  const j = await (await fetch("/api/log")).json();
  const el = document.getElementById("log");
  el.textContent = (j.lines||[]).join("\n");
  el.scrollTop = el.scrollHeight;
}
async function decide(ok){
  await fetch("/api/pair/decide",{method:"POST",body:JSON.stringify({approve:ok})});
  toast(ok ? "已允许连接" : "已拒绝连接");
  refresh();
}
async function setRoot(){ const r = await fetch("/api/root",{method:"POST",body:JSON.stringify({root:document.getElementById("root").value})}); if(!r.ok) toast(await r.text()); else toast("已保存"); refresh(); }
async function setCred(){ const r = await fetch("/api/cred",{method:"POST",body:JSON.stringify({user:document.getElementById("user").value,pass:document.getElementById("pass").value})}); if(!r.ok) toast(await r.text()); else toast("已更新"); refresh(); }
async function doAdb(){ await fetch("/api/adb",{method:"POST"}); refresh(); }
refresh(); poll(); setInterval(refresh,1200); setInterval(poll,2000);
</script></body></html>`

// ---------- 入口 ----------

func main() {
	port := flag.Int("port", 0, "监听端口（默认取配置文件，首次 8321）")
	root := flag.String("root", "", "备份落地目录（默认取配置文件，首次为 exe 同级 mibackups）")
	noBrowser := flag.Bool("no-browser", false, "不自动打开控制页")
	flag.Parse()

	c := loadConfig()
	if *port != 0 {
		c.Port = *port
	}
	rootDir := *root
	if rootDir == "" {
		rootDir = c.Root
	}
	if rootDir == "" {
		exe, err := os.Executable()
		if err == nil {
			rootDir = filepath.Join(filepath.Dir(exe), "mibackups")
		} else {
			rootDir = "mibackups"
		}
	}
	abs, err := filepath.Abs(rootDir)
	if err == nil {
		rootDir = abs
	}
	_ = os.MkdirAll(rootDir, 0o755)
	davFS.mu.Lock()
	davFS.root = rootDir
	davFS.mu.Unlock()
	c.Root = rootDir
	if *port != 0 {
		c.Port = *port
	}
	c.save()

	mux := http.NewServeMux()
	mux.HandleFunc("/dav/", func(w http.ResponseWriter, r *http.Request) { davHandler(w, r, c) })
	mux.HandleFunc("/dav", func(w http.ResponseWriter, r *http.Request) { davHandler(w, r, c) })
	// 手机端自动发现：USB 通道（adb reverse 后手机访问 127.0.0.1）也走这里探测
	mux.HandleFunc("/miback/info", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_, _ = w.Write(discoverPayload(c))
	})
	mux.HandleFunc("/pair/", func(w http.ResponseWriter, r *http.Request) { pairHandler(w, r, c) })
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) { controlHandler(w, r, c) })
	go serveDiscovery(c)

	addr := ":" + strconv.Itoa(c.Port)
	logf("mibackpc 启动：端口 %d，备份目录 %s", c.Port, rootDir)
	logf("局域网地址：%s", func() string {
		var s []string
		for _, ip := range lanIPs() {
			s = append(s, fmt.Sprintf("http://%s:%d/dav/", ip, c.Port))
		}
		if len(s) == 0 {
			return "（未检测到局域网 IP，请确认已连接 WiFi/网线）"
		}
		return strings.Join(s, "  ")
	}())
	if !*noBrowser {
		go func() {
			time.Sleep(300 * time.Millisecond)
			url := "http://127.0.0.1:" + strconv.Itoa(c.Port) + "/"
			_ = exec.Command("cmd", "/c", "start", "", url).Start()
			_ = mime.TypeByExtension(".html") // 保持 mime 包被引用
		}()
	}
	if err := http.ListenAndServe(addr, mux); err != nil {
		fmt.Println("监听失败：", err)
		fmt.Println("按回车退出...")
		_, _ = fmt.Scanln()
		os.Exit(1)
	}
}
