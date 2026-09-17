package com.suileyan.cloud.provider;

import org.json.JSONArray;
import org.json.JSONObject;

import com.suileyan.cloud.CloudAccount;
import com.suileyan.cloud.CloudException;
import com.suileyan.cloud.CloudProvider;
import com.suileyan.cloud.EncryptedCredStore;
import com.suileyan.cloud.LoginContext;
import com.suileyan.cloud.LoginState;
import com.suileyan.cloud.ProgressCallback;
import com.suileyan.cloud.RemoteEntry;
import com.suileyan.comm.LogHelp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

/**
 * 迅雷网盘（pan.xunlei.com）Provider —— x-api-pan OAuth API（thunder_browser 客户端）
 *
 * API 参考 AList drivers/thunder_browser（com.xunlei.browser 客户端）：
 * - 认证：OAuth2。WebView 网页登录后前端把 access_token/refresh_token 存 localStorage，
 *   捕获 refresh_token（存 "refresh_token" 键）；每次请求用 refresh 换新的 access_token
 *   （存 "access_token" 键），Authorization: Bearer <access_token>。
 *   refresh_token 流程无需验证码签名（仅账密登录才需要 captcha_sign）。
 * - 列表：GET /drive/v1/files?parent_id=&page_token=&space=&filters={"trashed":{"eq":false}}&with=url
 * - 建目录：POST /drive/v1/files（kind=drive#folder, name, parent_id, space）
 * - 上传：POST /drive/v1/files（kind=drive#file, name, size, hash=gcid, upload_type=UPLOAD_TYPE_RESUMABLE）
 *   → 返回 S3 resumable 参数（access_key_id/secret/security_token/bucket/endpoint/key/expiration）
 *   → S3 分片上传（AWS SigV4，region=xunlei）→ 完成
 * - 下载：GET /drive/v1/files/{fileID}?_magic=2021&space=&thumbnail_size=SIZE_LARGE&with=url
 *   → web_content_link 直链流式下载（带 DownloadUserAgent）
 * - 删除：POST /drive/v1/files:batchDelete（ids+space）
 * - token 刷新：POST /xluser-ssl.xunlei.com/v1/auth/token（grant_type=refresh_token）
 * - 错误码：4122/4121/10/16 → 刷新 token 重试；9 → captcha_invalid（登录态异常）
 *
 * 已知限制：
 * - 上传走 AWS S3 SigV4 签名（region=xunlei），实现较复杂；大文件分片由 CloudFileHelp 层统一处理，
 *   Provider 只处理整文件（单次 S3 上传，超 5GB 需分片——本项目备份文件通常小于此值）
 * - 迅雷有风控：refresh_token 长期有效但可能被风控吊销；access_token 有效期短，需频繁刷新
 * - 超级保险柜（SPACE_SAFE）等特殊空间未支持，仅普通网盘空间
 */
public class XunleiProvider implements CloudProvider {

    private static final String TAG = "XpMiBackup";
    public static final String TYPE = "xunlei";

    private static final String FILE_API_URL = "https://x-api-pan.xunlei.com/drive/v1/files";
    private static final String XLUSER_API_URL = "https://xluser-ssl.xunlei.com/v1";

    /** com.xunlei.browser 客户端凭据（AList thunder_browser 同款，公开常量） */
    private static final String CLIENT_ID = "ZUBzD9J_XPXfn7f7";
    private static final String CLIENT_SECRET = "yESVmHecEe6F0aou69vl-g";
    private static final String CLIENT_VERSION = "1.10.0.2633";
    private static final String PACKAGE_NAME = "com.xunlei.browser";

    private static final String KIND_FOLDER = "drive#folder";
    private static final String KIND_FILE = "drive#file";
    private static final String UPLOAD_TYPE_RESUMABLE = "UPLOAD_TYPE_RESUMABLE";
    /** 普通网盘空间（非超级保险柜） */
    private static final String SPACE = "";

    /** 下载 UA（AList 同款，AndroidDownloadManager） */
    private static final String DOWNLOAD_UA =
            "AndroidDownloadManager/13 (Linux; U; Android 13; M2004J7AC Build/SP1A.210812.016)";
    private static final MediaType JSON = MediaType.parse("application/json;charset=UTF-8");
    private static final int BUFFER_SIZE = 64 * 1024;

    private final CloudAccount account;
    /** 内存中的 access_token（refresh 后更新）；持久化在 EncryptedCredStore "access_token" 键 */
    private volatile String accessToken = "";

    private static OkHttpClient sClient;

    public XunleiProvider(CloudAccount account) {
        this.account = account;
    }

    @Override
    public String id() {
        return account != null ? account.id : "";
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return account != null && account.name != null && !account.name.isEmpty() ? account.name : "迅雷网盘";
    }

    @Override
    public boolean isLoggedIn() {
        return !refreshToken().isEmpty();
    }

    @Override
    public LoginState login(LoginContext ctx) {
        // 迅雷凭据来自 WebView 网页登录捕获 refresh_token，无应用内登录流程
        return LoginState.NOT_SUPPORTED;
    }

    // ========== 目录与列表 ==========

    @Override
    public boolean testConnection() throws CloudException {
        // 用根目录列表做轻量验证
        listParent("");
        return true;
    }

    @Override
    public List<String> listDirs() throws CloudException {
        var out = new ArrayList<String>();
        for (var e : listParent("")) {
            if (e.directory) out.add(e.name);
        }
        return out;
    }

    @Override
    public List<RemoteEntry> listEntries(String remoteDir) throws CloudException {
        var parentId = resolvePath(remoteDir, false);
        if (parentId == null) return new ArrayList<>();
        return listParent(parentId);
    }

    @Override
    public void mkdirs(String remoteDir) throws CloudException {
        resolvePath(remoteDir, true);
    }

    /** 列出目录下全部条目（分页，page_token 游标） */
    private List<Item> collectEntries(String parentId) throws CloudException {
        var items = new ArrayList<Item>();
        var pageToken = "";
        for (var guard = 0; guard < 200; guard++) {
            var params = new LinkedHashMap<String, String>();
            params.put("parent_id", parentId == null ? "" : parentId);
            params.put("page_token", pageToken);
            params.put("space", SPACE);
            params.put("filters", "{\"trashed\":{\"eq\":false}}");
            params.put("with", "url");
            params.put("with_audit", "true");
            params.put("thumbnail_size", "SIZE_LARGE");
            var json = apiGet(FILE_API_URL, params);
            var arr = json.optJSONArray("files");
            if (arr == null || arr.length() == 0) break;
            for (var i = 0; i < arr.length(); i++) {
                var obj = arr.optJSONObject(i);
                if (obj != null) items.add(Item.fromJson(obj));
            }
            pageToken = json.optString("next_page_token", "");
            if (pageToken.isEmpty()) break;
        }
        return items;
    }

    private List<RemoteEntry> listParent(String parentId) throws CloudException {
        var items = collectEntries(parentId);
        var out = new ArrayList<RemoteEntry>(items.size());
        for (var it : items) {
            out.add(new RemoteEntry(it.name, it.size, it.isDir, it.modifiedTime));
        }
        return out;
    }

    private Item findChild(String parentId, String name) throws CloudException {
        for (var it : collectEntries(parentId)) {
            if (it.name.equals(name)) return it;
        }
        return null;
    }

    private String createFolder(String parentId, String name) throws CloudException {
        try {
            var body = new JSONObject();
            body.put("kind", KIND_FOLDER);
            body.put("name", name);
            body.put("parent_id", parentId == null ? "" : parentId);
            body.put("space", SPACE);
            var json = apiPost(FILE_API_URL, body, null);
            var data = json.optJSONObject("file");
            if (data != null) {
                var id = data.optString("id", "");
                if (!id.isEmpty()) return id;
            }
            // 建目录异步生效：轮询查找
            for (var i = 0; i < 10; i++) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
                var child = findChild(parentId, name);
                if (child != null) return child.fileId;
            }
            throw new CloudException(CloudException.Kind.REMOTE, "迅雷建目录缺少 id: " + name);
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    /** 解析路径为目录 id；createMissing=true 自动建目录；失败返回 null */
    private String resolvePath(String path, boolean createMissing) throws CloudException {
        var v = trimSlashes(path);
        if (v.isEmpty()) return "";
        var parts = v.split("/");
        var parentId = "";
        for (var part : parts) {
            var name = cleanName(part);
            if (name.isEmpty()) continue;
            var child = findChild(parentId, name);
            if (child == null) {
                if (!createMissing) return null;
                parentId = createFolder(parentId, name);
                continue;
            }
            if (!child.isDir && !createMissing) return null;
            if (!child.isDir) {
                throw new CloudException(CloudException.Kind.REMOTE, "迅雷路径非目录: " + name);
            }
            parentId = child.fileId;
            if (parentId.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "迅雷缺少目录 id: " + name);
            }
        }
        return parentId;
    }

    private Item findEntry(String parentPath, String targetName) throws CloudException {
        var parentId = resolvePath(parentPath, false);
        if (parentId == null) return null;
        for (var it : collectEntries(parentId)) {
            if (it.name.equals(targetName)) return it;
        }
        return null;
    }

    // ========== 上传 ==========

    @Override
    public String upload(String localPath, String remoteDir) throws CloudException {
        uploadWithProgress(localPath, null, remoteDir, "");
        return "OK: " + localPath;
    }

    @Override
    public void uploadWithProgress(String localPath, ProgressCallback cb, String remoteDir, String taskId) throws CloudException {
        var localFile = new File(localPath);
        if (!localFile.exists()) {
            throw new CloudException(CloudException.Kind.LOCAL, "file not found: " + localPath);
        }
        try {
            var parentId = resolvePath(remoteDir, true);
            if (cb != null) cb.onStart(taskId);
            var size = localFile.length();
            if (size == 0) {
                // 迅雷不允许 0 字节文件（S3 上传空对象无意义）：mock 成功，恢复列表只依赖 descript.xml
                LogHelp.i(TAG, "迅雷跳过 0 字节文件: " + localPath);
                if (cb != null) cb.onFinish(taskId, 0, "success");
                return;
            }

            // 1. 计算 GCID（迅雷秒传/上传必需哈希）
            var gcid = gcidHex(localFile, size);

            // 2. 创建上传任务，获取 S3 resumable 参数
            var body = new JSONObject();
            body.put("kind", KIND_FILE);
            body.put("parent_id", parentId == null ? "" : parentId);
            body.put("name", localFile.getName());
            body.put("size", size);
            body.put("hash", gcid);
            body.put("upload_type", UPLOAD_TYPE_RESUMABLE);
            body.put("space", SPACE);
            var resp = apiPost(FILE_API_URL, body, null);
            var uploadType = resp.optString("upload_type", "");
            var resumable = resp.optJSONObject("resumable");
            if (!UPLOAD_TYPE_RESUMABLE.equals(uploadType) || resumable == null) {
                // 秒传命中：服务端直接返回 file 对象，无需上传
                if (resp.has("file")) {
                    LogHelp.i(TAG, "迅雷秒传命中: " + localFile.getName());
                    if (cb != null) cb.onProgress(taskId, size, size);
                    if (cb != null) cb.onFinish(taskId, 0, "success");
                    return;
                }
                throw new CloudException(CloudException.Kind.REMOTE,
                        "迅雷上传任务异常: " + truncate(resp.toString(), 300));
            }
            var params = resumable.optJSONObject("params");
            if (params == null) {
                throw new CloudException(CloudException.Kind.REMOTE, "迅雷上传缺少 S3 参数");
            }
            var accessKeyId = params.optString("access_key_id", "");
            var accessKeySecret = params.optString("access_key_secret", "");
            var securityToken = params.optString("security_token", "");
            var bucket = params.optString("bucket", "");
            var endpoint = params.optString("endpoint", "");
            var key = params.optString("key", "");
            var expiration = params.optLong("expiration", 0L);
            if (accessKeyId.isEmpty() || bucket.isEmpty() || endpoint.isEmpty() || key.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "迅雷上传 S3 参数不完整");
            }

            // 3. S3 分片上传（AWS SigV4，region=xunlei；单次上传整文件）
            s3PutObject(bucket, endpoint, key, accessKeyId, accessKeySecret, securityToken,
                    expiration, localFile, size, cb, taskId);
            if (cb != null) cb.onFinish(taskId, 0, "success");
        } catch (CloudException e) {
            if (cb != null) cb.onFinish(taskId, -1, e.getMessage());
            throw e;
        } catch (Exception e) {
            LogHelp.e(TAG, "迅雷上传失败", e);
            if (cb != null) cb.onFinish(taskId, -1, e.getMessage());
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    /**
     * S3 PUT Object（AWS SigV4 签名，region=xunlei）。
     * 单次上传整文件；超过 S3 单对象上限（5GB）时由 CloudFileHelp 层分片处理。
     */
    private void s3PutObject(String bucket, String endpoint, String key,
                             String accessKeyId, String accessKeySecret, String securityToken,
                             long expiration, File file, long size,
                             ProgressCallback cb, String taskId) throws Exception {
        // endpoint 形如 "https://xxx.s3.xunlei.com" 或 "xxx.s3.xunlei.com"
        var host = endpoint;
        if (host.startsWith("https://")) host = host.substring(8);
        if (host.startsWith("http://")) host = host.substring(7);
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        var scheme = endpoint.startsWith("http://") ? "http" : "https";
        var url = scheme + "://" + host + "/" + key;

        var now = System.currentTimeMillis();
        var amzDate = amzDate(now);
        var dateStamp = amzDate.substring(0, 8);
        var region = "xunlei";
        var service = "s3";

        var payloadHash = sha256Hex(file); // 整文件 SHA-256（S3 SigV4 需要 payload hash）
        var canonicalUri = "/" + key;
        var canonicalQuery = "";
        var canonicalHeaders = "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n"
                + "x-amz-security-token:" + securityToken + "\n";
        var signedHeaders = "host;x-amz-content-sha256;x-amz-date;x-amz-security-token";
        var canonicalRequest = "PUT\n" + canonicalUri + "\n" + canonicalQuery + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        var scope = dateStamp + "/" + region + "/" + service + "/aws4_request";
        var stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        var signingKey = hmacSha256(hmacSha256(hmacSha256(hmacSha256(
                ("AWS4" + accessKeySecret).getBytes(StandardCharsets.UTF_8), dateStamp),
                region), service), "aws4_request");
        var signature = hexLower(hmacSha256(signingKey, stringToSign));

        var builder = new Request.Builder().url(url)
                .header("Host", host)
                .header("x-amz-date", amzDate)
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-security-token", securityToken)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + scope
                        + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature)
                .put(new RequestBody() {
                    @Override
                    public MediaType contentType() {
                        return MediaType.parse("application/octet-stream");
                    }

                    @Override
                    public long contentLength() {
                        return size;
                    }

                    @Override
                    public void writeTo(BufferedSink sink) throws IOException {
                        var buffer = new byte[BUFFER_SIZE];
                        var remaining = size;
                        try (var in = new FileInputStream(file)) {
                            while (remaining > 0) {
                                var read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                                if (read == -1) break;
                                sink.write(buffer, 0, read);
                                remaining -= read;
                                if (cb != null) cb.onProgress(taskId, size - remaining, size);
                            }
                        }
                    }
                });
        try (var resp = client().newCall(builder.build()).execute()) {
            if (resp.code() < 200 || resp.code() >= 300) {
                var errBody = resp.body() != null ? resp.body().string() : "";
                // 完整打印 S3 错误（含 StringToSign/SignatureProvided 调试字段）便于定位签名差异
                LogHelp.e(TAG, "迅雷 S3 上传失败 HTTP " + resp.code() + ": " + truncate(errBody, 1500));
                throw new CloudException(CloudException.Kind.REMOTE,
                        "迅雷 S3 上传失败 HTTP " + resp.code() + ": " + truncate(errBody, 500));
            }
        }
    }

    // ========== 下载 ==========

    @Override
    public String downloadFile(String remotePath, String localPath) throws CloudException {
        try {
            var remote = trimSlashes(remotePath);
            var name = pathName(remote);
            var parent = pathParent(remote);
            var entry = findEntry(parent, name);
            if (entry == null) {
                throw new CloudException(CloudException.Kind.REMOTE, "迅雷文件不存在: " + remotePath);
            }
            if (entry.isDir) {
                throw new CloudException(CloudException.Kind.REMOTE, "迅雷目标为目录: " + remotePath);
            }
            var dl = downloadUrl(entry.fileId);
            if (dl.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "迅雷缺少下载地址: " + remotePath);
            }
            var builder = new Request.Builder().url(dl)
                    .header("User-Agent", DOWNLOAD_UA)
                    .header("Accept", "*/*");
            try (var resp = client().newCall(builder.build()).execute()) {
                var code = resp.code();
                if (code < 200 || code >= 300) {
                    throw new CloudException(CloudException.Kind.REMOTE, "迅雷下载 HTTP " + code);
                }
                var body = resp.body();
                if (body == null) {
                    throw new CloudException(CloudException.Kind.REMOTE, "迅雷下载空响应");
                }
                try (var out = new FileOutputStream(localPath); var in = body.byteStream()) {
                    var buffer = new byte[BUFFER_SIZE];
                    var read = 0;
                    var written = 0L;
                    var total = body.contentLength();
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        written += read;
                    }
                    if (total > 0 && written != total) {
                        throw new CloudException(CloudException.Kind.REMOTE,
                                "迅雷下载不完整: " + written + "/" + total);
                    }
                }
            }
            return "OK: " + remotePath + " -> " + localPath;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    private String downloadUrl(String fileId) throws CloudException {
        try {
            var params = new LinkedHashMap<String, String>();
            params.put("_magic", "2021");
            params.put("space", SPACE);
            params.put("thumbnail_size", "SIZE_LARGE");
            params.put("with", "url");
            var json = apiGet(FILE_API_URL + "/" + fileId, params);
            var file = json.optJSONObject("file");
            if (file == null) return "";
            return file.optString("web_content_link", "");
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    // ========== 删除 ==========

    @Override
    public void deleteDir(String remoteDir) throws CloudException {
        deletePath(remoteDir);
    }

    @Override
    public void deleteFile(String remotePath) throws CloudException {
        deletePath(remotePath);
    }

    private void deletePath(String remotePath) throws CloudException {
        try {
            var remote = trimSlashes(remotePath);
            if (remote.isEmpty()) return;
            var entry = findEntry(pathParent(remote), pathName(remote));
            if (entry == null || entry.fileId.isEmpty()) return;
            var ids = new JSONArray();
            ids.put(entry.fileId);
            var body = new JSONObject();
            body.put("ids", ids);
            body.put("space", SPACE);
            apiPost(FILE_API_URL + ":batchDelete", body, null);
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    // ========== token 刷新 ==========

    /**
     * 刷新 access_token：用 refresh_token 换新（POST /auth/token）。
     * 成功更新内存 + 持久化 access_token（refresh_token 可能轮换，一并更新）
     */
    @Override
    public boolean refresh() {
        var rt = refreshToken();
        if (rt.isEmpty()) return false;
        try {
            var body = new JSONObject();
            body.put("grant_type", "refresh_token");
            body.put("refresh_token", rt);
            body.put("client_id", CLIENT_ID);
            body.put("client_secret", CLIENT_SECRET);
            var resp = httpPost(XLUSER_API_URL + "/auth/token", baseHeaders(), body.toString(), null);
            if (resp.code < 200 || resp.code >= 300) {
                LogHelp.w(TAG, "迅雷 refresh HTTP " + resp.code + ": " + truncate(resp.body, 200));
                return false;
            }
            var json = new JSONObject(resp.body);
            var at = json.optString("access_token", "");
            var newRt = json.optString("refresh_token", "");
            if (at.isEmpty()) {
                LogHelp.w(TAG, "迅雷 refresh 无 access_token");
                return false;
            }
            accessToken = at;
            EncryptedCredStore.put(account.id, "access_token", at);
            if (!newRt.isEmpty() && !newRt.equals(rt)) {
                EncryptedCredStore.put(account.id, "refresh_token", newRt);
            }
            return true;
        } catch (Exception e) {
            LogHelp.w(TAG, "迅雷 refresh 失败", e);
            return false;
        }
    }

    // ========== API 封装 ==========

    private static class HttpResponse {
        int code;
        String body = "";
    }

    private String refreshToken() {
        return EncryptedCredStore.get(account.id, "refresh_token");
    }

    /** 获取有效 access_token：优先内存，其次持久化；为空则尝试 refresh */
    private String accessToken() throws CloudException {
        if (!accessToken.isEmpty()) return accessToken;
        var stored = EncryptedCredStore.get(account.id, "access_token");
        if (!stored.isEmpty()) {
            accessToken = stored;
            return stored;
        }
        if (refresh()) {
            return accessToken;
        }
        throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "迅雷未登录或凭据已失效");
    }

    private Map<String, String> baseHeaders() {
        var h = new LinkedHashMap<String, String>();
        h.put("Accept", "application/json;charset=UTF-8");
        h.put("Content-Type", "application/json");
        h.put("x-client-id", CLIENT_ID);
        h.put("x-client-version", CLIENT_VERSION);
        h.put("x-device-id", deviceId());
        return h;
    }

    /** 设备 id：由 refresh_token 派生（稳定），对齐 AList 的 MD5(refreshToken) */
    private String deviceId() {
        try {
            return md5Hex(refreshToken());
        } catch (Exception e) {
            return md5Hex(account.id);
        }
    }

    /** GET API；401/4122 等刷新 token 重试一次 */
    private JSONObject apiGet(String url, Map<String, String> params) throws CloudException {
        var resp = httpGet(url, apiHeaders(), params);
        if (isAuthError(resp)) {
            if (refresh()) {
                resp = httpGet(url, apiHeaders(), params);
            } else {
                throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "迅雷认证失败且刷新失败");
            }
        }
        return parseResponse(resp);
    }

    /** POST JSON API；401/4122 等刷新 token 重试一次 */
    private JSONObject apiPost(String url, JSONObject data, Map<String, String> extraParams) throws CloudException {
        var resp = httpPost(url, apiHeaders(), data.toString(), extraParams);
        if (isAuthError(resp)) {
            if (refresh()) {
                resp = httpPost(url, apiHeaders(), data.toString(), extraParams);
            } else {
                throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "迅雷认证失败且刷新失败");
            }
        }
        return parseResponse(resp);
    }

    /** 认证相关错误：HTTP 401，或 error_code 4122/4121/10/16（token 失效） */
    private boolean isAuthError(HttpResponse resp) {
        if (resp.code == 401 || resp.code == 403) return true;
        try {
            var code = new JSONObject(resp.body).optInt("error_code", 0);
            return code == 4122 || code == 4121 || code == 10 || code == 16;
        } catch (Exception e) {
            return false;
        }
    }

    /** 统一响应解析：error_code 非 0 → 异常；HTTP 非 2xx → 异常 */
    private JSONObject parseResponse(HttpResponse resp) throws CloudException {
        if (resp.code < 200 || resp.code >= 300) {
            throw new CloudException(CloudException.Kind.REMOTE,
                    "迅雷 API HTTP " + resp.code + ": " + truncate(resp.body, 200));
        }
        try {
            var json = new JSONObject(resp.body);
            var code = json.optInt("error_code", 0);
            if (code != 0) {
                var msg = json.optString("error", "未知错误");
                if (code == 9) {
                    // captcha_invalid / space_token_invalid：登录态异常
                    throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "迅雷登录态异常: " + msg);
                }
                throw new CloudException(CloudException.Kind.REMOTE,
                        "迅雷 API 错误 error_code=" + code + ": " + msg);
            }
            return json;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, "迅雷响应非 JSON: " + truncate(resp.body, 200));
        }
    }

    private Map<String, String> apiHeaders() throws CloudException {
        var h = baseHeaders();
        h.put("Authorization", "Bearer " + accessToken());
        return h;
    }

    private HttpResponse httpGet(String url, Map<String, String> headers, Map<String, String> params) throws CloudException {
        var parsed = okhttp3.HttpUrl.parse(url);
        if (parsed == null) {
            throw new CloudException(CloudException.Kind.NETWORK, "invalid url: " + url);
        }
        var ub = parsed.newBuilder();
        if (params != null) {
            for (var e : params.entrySet()) ub.addQueryParameter(e.getKey(), e.getValue());
        }
        var builder = new Request.Builder().url(ub.build());
        for (var e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        return execute(builder.build());
    }

    private HttpResponse httpPost(String url, Map<String, String> headers, String body, Map<String, String> extraParams) throws CloudException {
        var parsed = okhttp3.HttpUrl.parse(url);
        if (parsed == null) {
            throw new CloudException(CloudException.Kind.NETWORK, "invalid url: " + url);
        }
        var ub = parsed.newBuilder();
        if (extraParams != null) {
            for (var e : extraParams.entrySet()) ub.addQueryParameter(e.getKey(), e.getValue());
        }
        var builder = new Request.Builder().url(ub.build());
        for (var e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        builder.post(RequestBody.create(JSON, body == null ? "" : body));
        return execute(builder.build());
    }

    private HttpResponse execute(Request request) throws CloudException {
        var resp = new HttpResponse();
        try (var r = client().newCall(request).execute()) {
            resp.code = r.code();
            resp.body = r.body() != null ? r.body().string() : "";
            return resp;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.NETWORK, e);
        }
    }

    private static OkHttpClient client() {
        if (sClient != null) return sClient;
        synchronized (XunleiProvider.class) {
            if (sClient != null) return sClient;
            sClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(300, TimeUnit.SECONDS)
                    .build();
            return sClient;
        }
    }

    // ========== 哈希 / 签名工具 ==========

    /**
     * 迅雷 GCID：分块 SHA1（块大小 256KB~2MB，按文件大小自适应）→ 各块 SHA1 摘要再整体 SHA1。
     * 对齐 AList getGcid：calcBlockSize 使 块数 ≤ 512 且块大小 ≤ 2MB。
     */
    private static String gcidHex(File file, long size) throws Exception {
        var blockSize = 0x40000L; // 256KB
        while ((double) size / blockSize > 0x200 && blockSize < 0x200000) {
            blockSize = blockSize << 1;
        }
        var outer = MessageDigest.getInstance("SHA-1");
        var inner = MessageDigest.getInstance("SHA-1");
        var buffer = new byte[BUFFER_SIZE];
        try (var in = new FileInputStream(file)) {
            var remaining = size;
            while (remaining > 0) {
                inner.reset();
                var toRead = (int) Math.min(blockSize, remaining);
                var readInBlock = 0;
                while (readInBlock < toRead) {
                    var read = in.read(buffer, 0, (int) Math.min(buffer.length, toRead - readInBlock));
                    if (read == -1) break;
                    inner.update(buffer, 0, read);
                    readInBlock += read;
                }
                outer.update(inner.digest());
                remaining -= readInBlock;
            }
        }
        return hexLower(outer.digest());
    }

    private static String md5Hex(String text) throws Exception {
        return hexLower(MessageDigest.getInstance("MD5")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256Hex(File file) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        var buffer = new byte[BUFFER_SIZE];
        try (var in = new FileInputStream(file)) {
            var read = 0;
            while ((read = in.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
        }
        return hexLower(md.digest());
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return hexLower(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    /** AWS SigV4 时间戳：yyyyMMdd'T'HHmmss'Z'（UTC） */
    private static String amzDate(long millis) {
        var fmt = new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return fmt.format(new java.util.Date(millis));
    }

    private static String hexLower(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (var b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    // ========== 工具 ==========

    private static String trimSlashes(String path) {
        var v = path == null ? "" : path.replace('\\', '/');
        while (v.startsWith("/")) v = v.substring(1);
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static String pathParent(String path) {
        var v = trimSlashes(path);
        var i = v.lastIndexOf('/');
        return i < 0 ? "" : v.substring(0, i);
    }

    private static String pathName(String path) {
        var v = trimSlashes(path);
        var i = v.lastIndexOf('/');
        return i < 0 ? v : v.substring(i + 1);
    }

    /** 清理文件名中的控制字符/零宽字符并 trim（对齐 Quark Provider） */
    private static String cleanName(String name) {
        if (name == null) return "";
        var out = new StringBuilder();
        for (var i = 0; i < name.length(); i++) {
            var c = name.charAt(i);
            var code = (int) c;
            if ((code >= 0x0000 && code <= 0x001F) || (code >= 0x007F && code <= 0x009F)
                    || (code >= 0x200B && code <= 0x200F) || code == 0xFEFF) {
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** 目录/文件条目 */
    private static class Item {
        final String fileId;
        final String name;
        final long size;
        final boolean isDir;
        final long modifiedTime;

        Item(String fileId, String name, long size, boolean isDir, long modifiedTime) {
            this.fileId = fileId;
            this.name = name;
            this.size = size;
            this.isDir = isDir;
            this.modifiedTime = modifiedTime;
        }

        static Item fromJson(JSONObject obj) {
            var fileId = obj.optString("id", "");
            var name = obj.optString("name", "");
            var isDir = KIND_FOLDER.equals(obj.optString("kind", ""));
            var modified = obj.optLong("modified_time", 0L);
            if (modified == 0L) modified = System.currentTimeMillis();
            return new Item(fileId, name, obj.optLong("size", 0L), isDir, modified);
        }
    }
}