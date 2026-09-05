# mibackpc —— 电脑端备份接收器

把电脑上的任意文件夹变成一台 **WebDAV 服务器**，手机端 XpMiBackup 的
「备份到电脑」入口直连它，备份/恢复数据直接落到电脑磁盘。

单文件 exe、纯 Go 标准库、零第三方依赖、无需安装。

## 为什么是 WebDAV 而不是 FTP

手机端 `WebdavFileHelp`（OkHttp 客户端）是既有且经过真机验证的链路：
切片、断点、进度回调、AUTH 处理全部复用。选 FTP 意味着在无真机调试的
前提下从零写一个「控制连接 + PASV 数据连接」客户端，风险完全不可控。
PC 端实现 WebDAV 子集只有约 250 行 Go，且两端源码都在仓库里，可代码级互验
（见下文「验证」）。

## 使用

1. 电脑双击 `mibackpc.exe`，自动打开控制页 `http://127.0.0.1:8321/`
   （首次自动生成随机密码，账号密码在控制页可查可改）
2. 控制页确认「备份落地目录」
3. 手机：设置 → 云备份助手 → 备份页 → **备份到电脑**：
   - 同一 WiFi：填控制页显示的局域网地址（如 `192.168.1.3:8321`）
   - USB 数据线：控制页点「重新建立 adb reverse」（需电脑装有 adb 并连接手机），
     手机填 `127.0.0.1:8321`
4. 测试并保存 → 开始备份（后续与普通 WebDAV 备份完全一致）

命令行参数：`-port 8321` `-root D:\备份目录` `-no-browser`

## 安全

- `/dav/*` 需要 Basic 认证（首次启动随机生成密码，`mibackpc.json` 保存）
- 控制页与 `/api/*` **只允许本机回环访问**，局域网设备无法控制电脑端
- 路径越界防护（`..` / 绝对路径 / 盘符一律拒绝）
- PUT 落盘为 `.mibackpart` 临时文件再 rename，断连不产生半截文件
  （手机端分片重试不会把坏分片当有效数据）

## 实现的 WebDAV 子集（对齐手机端 WebdavFileHelp）

| 方法 | 语义 | 对端行为 |
|---|---|---|
| PROPFIND Depth 0/1 | **207 Multi-Status**，首条 href 为目录自身 | `testConnection()` 以 207 判定连通；列表正则解析 href/getcontentlength/collection |
| MKCOL | 递归建目录，已存在返回 405 | `mkdirs()` 逐级创建并吞掉异常 |
| PUT | 2xx 成功 | 上传/分片上传 |
| GET/HEAD | 文件内容（支持 Range） | 恢复下载 |
| DELETE | 递归删除，2xx/3xx 视为成功 | 旧备份清理 |
| OPTIONS | DAV: 1,2 | 兼容探测 |

## 验证（无真机，代码级逻辑通路）

1. **黑盒**：`python blackbox_test.py`（23 项）——按客户端线上格式逐端点打真实
   服务端：207/正则解析/中文 URL 解码/分片重传覆盖/越界/401/递归删除
2. **端到端**：`harness/run.sh`（11 项）——把**应用真实源码**
   `WebdavFileHelp` + `ConfigHelp` 编译到桌面 JVM（stub `android.util.Log` +
   `layoutlib.jar` 提供运行期实现），直接打 Go 服务端：
   testConnection → mkdirs → upload/uploadToWebdav(进度回调) → listDirs/listEntries
   → 中文名+空格上传下载字节比对 → deleteFile/deleteDir
3. **应用构建**：`:app:assembleDebug` + `lint`（NewApi 0）
4. **真机**（warsaw / Android 17）：备份页入口可见、测试并保存成功、
   智能存储页「开始备份」落到电脑目录（待晨间人工确认）

## 构建

```bash
cd pc
GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o mibackpc.exe .
```
