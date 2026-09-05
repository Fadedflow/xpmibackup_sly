// mibackpc —— XpMiBackup 电脑端接收器
//
// 单文件、纯标准库、零 CGO：把任意文件夹变成一台 WebDAV 服务器，
// 供手机端 XpMiBackup 的「WebDAV 方案」直接备份/恢复到电脑。
//
// 传输通道二选一（客户端 URL 不同而已，服务端无感知）：
//
//	局域网：手机填 http://<电脑局域网IP>:8321/dav/
//	USB  ：本工具自动执行 adb reverse tcp:8321 tcp:8321，手机填 http://127.0.0.1:8321/dav/
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
	Root string `json:"root"` // 备份文件落地目录
	Port int    `json:"port"`
	User string `json:"user"`
	Pass string `json:"pass"`
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
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"root":   davRoot(),
			"port":   c.Port,
			"user":   c.User,
			"pass":   c.Pass,
			"lanIPs": lanIPs(),
			"adbURL": fmt.Sprintf("http://127.0.0.1:%d/dav/", c.Port),
			"adbOK":  adbReverse(c.Port),
		})

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
		ok := adbReverse(c.Port)
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": ok})

	case r.URL.Path == "/" || r.URL.Path == "/index.html":
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = io.WriteString(w, indexHTML)

	default:
		http.NotFound(w, r)
	}
}

// adbReverse 尽力建立 USB 通道：手机访问 127.0.0.1:port 即到达本机
func adbReverse(port int) bool {
	for _, exe := range []string{"adb", "adb.exe"} {
		if p, err := exec.LookPath(exe); err == nil {
			cmd := exec.Command(p, "reverse", fmt.Sprintf("tcp:%d", port), fmt.Sprintf("tcp:%d", port))
			if out, err := cmd.CombinedOutput(); err == nil {
				logf("adb reverse 建立 USB 通道成功 (%d)", port)
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

// ---------- 控制页 ----------

const indexHTML = `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>mibackpc · 电脑备份接收器</title>
<style>
 body{font-family:"Segoe UI",system-ui,sans-serif;background:#141517;color:#e6e6e6;margin:0;padding:24px;max-width:760px;margin-inline:auto}
 h1{font-size:20px;font-weight:600} h1 span{color:#8ab4f8}
 code{background:#232428;padding:2px 6px;border-radius:4px;font-size:13px;user-select:all}
 .card{background:#1d1e22;border:1px solid #2c2d31;border-radius:10px;padding:16px;margin-bottom:16px}
 label{display:block;font-size:12px;color:#9aa0a6;margin:10px 0 4px}
 input{width:100%;box-sizing:border-box;background:#232428;border:1px solid #3c3d42;color:#e6e6e6;border-radius:6px;padding:8px;font-size:14px}
 button{background:#8ab4f8;border:0;color:#141517;border-radius:6px;padding:8px 14px;font-size:14px;font-weight:600;cursor:pointer}
 button.gray{background:#3c3d42;color:#e6e6e6}
 .row{display:flex;gap:8px;align-items:flex-end}
 .row>div{flex:1}
 .url{font-size:15px;margin:6px 0;word-break:break-all}
 .ok{color:#81c995}.no{color:#f28b82}
 #log{background:#101114;border:1px solid #2c2d31;border-radius:6px;height:220px;overflow:auto;padding:10px;font:12px/1.5 Consolas,monospace;white-space:pre-wrap;color:#bdc1c6}
</style></head><body>
<h1>mibackpc · <span>电脑备份接收器</span></h1>
<div class="card">
 <div class="url">局域网（手机与电脑同一 WiFi）：<code id="lanURL">…</code></div>
 <div class="url">USB（需数据线 + 已开 USB 调试）：<code id="adbURL">…</code> <span id="adbState"></span></div>
 <div class="url">账号：<code id="cred">…</code></div>
 <div>手机端：备份页 → 备份到电脑 → 填上方地址 → 测试并保存 → 开始备份</div>
</div>
<div class="card">
 <label>备份落地目录</label>
 <div class="row"><div><input id="root"></div><button onclick="setRoot()">保存</button></div>
 <label>WebDAV 账号 / 密码</label>
 <div class="row"><div><input id="user"></div><div><input id="pass"></div><button onclick="setCred()">更新</button></div>
 <label>USB 通道（需要电脑已安装 adb 并连接手机）</label>
 <button class="gray" onclick="doAdb()">重新建立 adb reverse</button>
</div>
<div class="card"><label>传输日志</label><div id="log"></div></div>
<script>
const esc = s => (s||"").replace(/&/g,"&amp;").replace(/</g,"&lt;");
async function refresh(){
  const st = await (await fetch("/api/state")).json();
  document.getElementById("root").value = st.root || "";
  document.getElementById("user").value = st.user || "";
  document.getElementById("pass").value = st.pass || "";
  document.getElementById("lanURL").textContent = (st.lanIPs||[]).map(ip => "http://"+ip+":"+st.port+"/dav/").join("  或  ") || "未检测到局域网 IP";
  document.getElementById("adbURL").textContent = st.adbURL;
  document.getElementById("adbState").innerHTML = st.adbOK ? '<span class="ok">已建立</span>' : '<span class="no">未建立</span>';
  document.getElementById("cred").textContent = st.user + " / " + st.pass;
}
async function poll(){
  const j = await (await fetch("/api/log")).json();
  document.getElementById("log").textContent = (j.lines||[]).join("\n");
}
async function setRoot(){ const r = await fetch("/api/root",{method:"POST",body:JSON.stringify({root:document.getElementById("root").value})}); if(!r.ok) alert(await r.text()); refresh(); }
async function setCred(){ const r = await fetch("/api/cred",{method:"POST",body:JSON.stringify({user:document.getElementById("user").value,pass:document.getElementById("pass").value})}); if(!r.ok) alert(await r.text()); refresh(); }
async function doAdb(){ await fetch("/api/adb",{method:"POST"}); refresh(); }
refresh(); poll(); setInterval(poll,2000);
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
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) { controlHandler(w, r, c) })

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
