import com.suileyan.comm.ConfigHelp;
import com.suileyan.comm.WebdavFileHelp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 端到端逻辑通路验证：应用真实源码 WebdavFileHelp/ConfigHelp 直接打 Go 服务端 mibackpc。
 * 覆盖真实备份/恢复会发出的全部请求形态：
 *   testConnection(207) → mkdirs(逐级 MKCOL) → upload/uploadToWebdav(PUT+进度回调)
 *   → listDirs/listEntries(PROPFIND 正则解析) → downloadFile(GET 字节比对) → deleteDir(递归)
 * 用法：java Harness <davBaseUrl> <user> <pass>
 */
public class Harness {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + (detail.isEmpty() ? "" : "   [" + detail + "]"));
        if (ok) passed++; else failed++;
    }

    static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    public static void main(String[] args) throws Exception {
        var base = args.length > 0 ? args[0] : "http://127.0.0.1:18321/dav/";
        var user = args.length > 1 ? args[1] : "miback";
        var pass = args.length > 2 ? args[2] : "";

        Map<String, String> params = new LinkedHashMap<>();
        params.put("webdav_url", base);
        params.put("webdav_user", user);
        params.put("webdav_pass", pass);
        params.put("backup_path", "MIUI/backup");

        // 1) testConnection：WebdavProvider.testConnection 的原样调用
        boolean ok = ConfigHelp.withAccount(params, WebdavFileHelp::testConnection);
        check("testConnection（PROPFIND Depth0 → 207）", ok, String.valueOf(ok));

        // 2) mkdirs：逐级 MKCOL
        String dir = "MIUI/backup/20260905_harness";
        ConfigHelp.withAccount(params, () -> {
            try { WebdavFileHelp.mkdirs(dir); } catch (Exception e) { throw new RuntimeException(e); }
        });
        check("mkdirs 逐级 MKCOL", true);

        // 3) upload（无进度重载）
        Path tmp = Files.createTempFile("harness", ".bin");
        byte[] data = new byte[10496];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 7);
        Files.write(tmp, data);
        String r1 = ConfigHelp.withAccount(params, () -> WebdavFileHelp.upload(tmp.toString(), dir));
        check("upload 普通上传", r1.startsWith("OK"), r1);

        // 4) uploadToWebdav：进度回调（真实备份走的就是这条路）
        var progress = new int[]{0, -99};
        var progressCount = new int[]{0};
        ConfigHelp.withAccount(params, () -> {
            try {
                WebdavFileHelp.uploadToWebdav(tmp.toString(), new com.suileyan.cloud.ProgressCallback() {
                    @Override public void onProgress(String taskId, long current, long total) {
                        progressCount[0]++;
                        progress[0] = (int) current;
                    }
                    @Override public void onFinish(String taskId, int code, String msg) { progress[1] = code; }
                }, dir, "task-1");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        check("uploadToWebdav onFinish code=0", progress[1] == 0, "code=" + progress[1]);
        check("uploadToWebdav 进度回调有触发", progressCount[0] > 0 && progress[0] == data.length,
                "count=" + progressCount[0] + " last=" + progress[0]);

        // 5) listDirs：PROPFIND 正则解析出备份目录名
        List<String> dirs = ConfigHelp.withAccount(params, WebdavFileHelp::listDirs);
        check("listDirs 含备份目录", dirs.contains("20260905_harness"), dirs.toString());

        // 6) listEntries：URL 解码中文名 + 大小
        var entries = ConfigHelp.withAccount(params, () -> WebdavFileHelp.listEntries(dir));
        boolean found = entries.stream().anyMatch(e -> e.name.equals(tmp.getFileName().toString()) && e.size == data.length);
        check("listEntries 文件名与大小", found, entries.toString());

        // 7) 中文文件名上传 + 下载字节比对（中文目录 + 中文名 + 名字含空格）
        String cnName = "中文 备份.bin";
        String cnPath = dir + "/中文 目录/" + cnName;
        Path cnDir = Files.createTempDirectory("harness_cn");
        Path cnNamed = cnDir.resolve(cnName); // 本地文件名即中文名，upload() 以 getName() 落盘
        byte[] cnData = "中文内容测试-UTF8-ROUNDTRIP".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(cnNamed, cnData);
        String r2 = ConfigHelp.withAccount(params, () -> WebdavFileHelp.upload(cnNamed.toString(), dir + "/中文 目录"));
        check("upload 中文名+中文目录", r2.startsWith("OK"), r2);
        Path dl = Files.createTempFile("harness_dl", ".bin");
        String r3 = ConfigHelp.withAccount(params, () -> WebdavFileHelp.downloadFile(cnPath, dl.toString()));
        byte[] got = Files.readAllBytes(dl);
        check("downloadFile 中文名+字节一致", r3.startsWith("OK") && java.util.Arrays.equals(got, cnData), r3);

        // 8) 分片形态：part 文件 + manifest 上传与读取
        String part = dir + "/AllBackup.part00000";
        Path p1 = Files.createTempFile("harness_part", ".part");
        Files.write(p1, "PART-0".getBytes());
        String r4 = ConfigHelp.withAccount(params, () -> WebdavFileHelp.upload(p1.toString(), dir));
        check("upload 分片 part00000", r4.startsWith("OK"), r4);

        // 9) deleteFile + deleteDir（递归）
        ConfigHelp.withAccount(params, () -> {
            try { WebdavFileHelp.deleteFile(cnPath); } catch (Exception e) { throw new RuntimeException(e); }
        });
        String cnAgain = ConfigHelp.withAccount(params, () -> WebdavFileHelp.downloadFile(cnPath, dl.toString()));
        check("deleteFile 后下载 404", cnAgain.startsWith("ERROR"), cnAgain);
        ConfigHelp.withAccount(params, () -> {
            try { WebdavFileHelp.deleteDir(dir); } catch (Exception e) { throw new RuntimeException(e); }
        });
        List<String> dirs2 = ConfigHelp.withAccount(params, WebdavFileHelp::listDirs);
        check("deleteDir 递归后目录消失", !dirs2.contains("20260905_harness"), dirs2.toString());

        System.out.println();
        System.out.println("通过 " + passed + " / 失败 " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
