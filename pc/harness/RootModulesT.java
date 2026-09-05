import com.suileyan.comm.RootModulesHelp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * RootModulesHelp 桌面单测（无 Android 依赖部分）：tar 选择/清理/清单 JSON/命令构造/su 失败降级。
 * 用法：java RootModulesT
 */
public class RootModulesT {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + (detail.isEmpty() ? "" : "   [" + detail + "]"));
        if (ok) passed++; else failed++;
    }

    static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    public static void main(String[] args) throws Exception {
        var dir = Files.createTempDirectory("rmtest").toFile();

        // 1) 常量：feature id 不与引擎 0-13 冲突；SP key 拼写
        check("FEATURE_ID=90（避开引擎 0-13）", RootModulesHelp.FEATURE_ID == 90);
        check("spKey 拼写", RootModulesHelp.spKey().equals("files_for_backup_90"), RootModulesHelp.spKey());
        check("源路径含三家管理器", RootModulesHelp.SOURCE_PATHS.length == 4
                && RootModulesHelp.SOURCE_PATHS[0].equals("/data/adb/modules")
                && RootModulesHelp.SOURCE_PATHS[2].equals("/data/adb/ksu")
                && RootModulesHelp.SOURCE_PATHS[3].equals("/data/adb/ap"));

        // 2) 空目录 newestTar = null
        check("空目录 newestTar=null", RootModulesHelp.newestTar(dir.getPath()) == null);

        // 3) newestTar：只认 root_modules_*.tar，取最新
        var f1 = new File(dir, "root_modules_20260904.tar");
        var f2 = new File(dir, "root_modules_20260905.tar");
        var noise = new File(dir, "other.txt");
        var badPrefix = new File(dir, "modules.tar");
        Files.write(f1.toPath(), new byte[]{1});
        Files.write(f2.toPath(), new byte[]{2});
        Files.write(noise.toPath(), new byte[]{3});
        Files.write(badPrefix.toPath(), new byte[]{4});
        f1.setLastModified(1000_000L);
        f2.setLastModified(2000_000L);
        var newest = RootModulesHelp.newestTar(dir.getPath());
        check("newestTar 取最新且忽略干扰文件", newest != null && newest.getName().equals("root_modules_20260905.tar"),
                newest == null ? "null" : newest.getName());

        // 4) pruneOldTars：保留最新 2 个
        var f3 = new File(dir, "root_modules_20260906.tar");
        Files.write(f3.toPath(), new byte[]{5});
        f3.setLastModified(3000_000L);
        var removed = RootModulesHelp.pruneOldTars(dir.getPath(), 2);
        check("pruneOldTars 删除 1 个", removed == 1, "removed=" + removed);
        check("pruneOldTars 最新 2 个保留", f3.exists() && f2.exists() && !f1.exists());
        check("pruneOldTars 不误删无关文件", noise.exists());

        // 5) 清单 JSON：元素为「内嵌 JSON 对象文本的字符串」（对齐引擎 n() 写入侧）。
        //    宿主扫描：(String) it.next() 强转 → new JSONObject(str) → getString("path")/getLong("size")
        // 用真实存在的临时文件（引擎对齐过滤：文件必须存在且 size>0）
        var realTar = Files.createTempFile("root_modules_test", ".tar");
        Files.write(realTar, new byte[]{1, 2, 3});
        var json = RootModulesHelp.buildFileListJson(realTar.toString(), 3L);
        var parsed = new org.json.JSONArray(json);
        // 模拟 Gson 反序列化（对象元素→LinkedTreeMap；字符串元素→String），再模拟宿主强转
        Object element = parsed.get(0);
        boolean stringCastOk = false;
        String inner = null;
        try {
            inner = (String) element;
            stringCastOk = true;
        } catch (ClassCastException e) {
            stringCastOk = false;
        }
        check("清单元素可强转 String（不触发 ClassCastException）", stringCastOk, json);
        var innerObj = new org.json.JSONObject(inner);
        check("内嵌对象 path 字段", innerObj.getString("path").equals(realTar.toString()), json);
        check("内嵌对象 size 字段", innerObj.getLong("size") == 3L, json);
        // size<=0 或文件不存在时不应输出条目（对齐引擎过滤）
        var empty = RootModulesHelp.buildFileListJson("/nonexistent/x.tar", 0L);
        check("无效条目输出空清单", empty.equals("[]"), empty);

        // 6) 命令构造
        var probe = RootModulesHelp.buildProbeCommand();
        check("探测命令含全部候选 + 静默缺失", probe.startsWith("ls -d /data/adb/modules") && probe.endsWith("2>/dev/null"), probe);
        var tarCmd = RootModulesHelp.buildTarCommand("/sdcard/x/root_modules_1.tar",
                List.of("/data/adb/modules", "/data/adb/ksu"));
        check("打包命令拼接", tarCmd.startsWith("tar -cf /sdcard/x/root_modules_1.tar /data/adb/modules /data/adb/ksu") && tarCmd.contains("chmod 644"), tarCmd);

        // 7) su 在桌面不存在 → createTarViaSu 优雅降级返回错误（不抛异常）
        var err = RootModulesHelp.createTarViaSu(dir.getPath(), List.of());
        check("su 不可用时优雅降级", err != null && err.contains("su"), err);

        // 8) tar 缺失场景下 newestTar 仍为最新（f3）
        check("newestTar 稳定性", RootModulesHelp.newestTar(dir.getPath()) != null
                && RootModulesHelp.newestTar(dir.getPath()).getName().equals("root_modules_20260906.tar"));

        // 9) 管理器推断：包名优先（可区分 SukiSU/KernelSU），目录特征兜底
        check("包名识别 SukiSU", RootModulesHelp.managerName(
                List.of("/data/adb/ksu"), List.of("com.sukisu.ultra")).equals("SukiSU"));
        check("包名识别 KernelSU", RootModulesHelp.managerName(
                List.of("/data/adb/ksu"), List.of("me.weishu.kernelsu")).equals("KernelSU"));
        check("包名识别 APatch", RootModulesHelp.managerName(
                List.of("/data/adb/ap"), List.of("me.bmax.apatch")).equals("APatch"));
        check("包名识别 Magisk", RootModulesHelp.managerName(
                List.of("/data/adb/modules"), List.of("com.topjohnwu.magisk")).equals("Magisk"));
        check("目录特征 APatch 优先于 KSU", RootModulesHelp.managerName(
                List.of("/data/adb/ksu", "/data/adb/ap"), List.of()).equals("APatch"));
        check("目录特征 KSU", RootModulesHelp.managerName(
                List.of("/data/adb/modules", "/data/adb/ksu"), List.of()).equals("KernelSU"));
        check("目录特征 Magisk", RootModulesHelp.managerName(
                List.of("/data/adb/modules"), List.of()).equals("Magisk"));
        check("全未知回退 Root", RootModulesHelp.managerName(List.of(), List.of()).equals("Root"));

        // 10) 动态标题
        check("标题 zh", RootModulesHelp.buildTitle("SukiSU", true).equals("SukiSU 模块备份"));
        check("标题 en", RootModulesHelp.buildTitle("APatch", false).equals("APatch modules"));

        // 11) 管理器标记文件写入/回读
        RootModulesHelp.writeManagerMarker(dir.getPath(), "SukiSU", "com.sukisu.ultra");
        check("管理器标记回读", "SukiSU".equals(RootModulesHelp.readManagerName(dir.getPath())));
        check("管理器包名回读", "com.sukisu.ultra".equals(RootModulesHelp.readManagerPackage(dir.getPath())));
        check("标记缺失返回 null", RootModulesHelp.readManagerName(dir.getPath() + "/nope") == null);

        System.out.println();
        // ---------- 二期：恢复逻辑 ----------
        // 9) 条目白名单校验
        var ok = RootModulesHelp.validateEntries(java.util.List.of(
                "/data/adb/modules/x/module.prop", "data/adb/ksu/bin",
                "/data/adb/ap/", "/data/adb/modules_update/y"));
        check("白名单条目全部放行", ok == null, String.valueOf(ok));
        var bad1 = RootModulesHelp.validateEntries(java.util.List.of(
                "/data/adb/modules/a", "/data/adb/../home/x"));
        check("拒绝 .. 穿越路径", bad1 != null && bad1.contains(".."), String.valueOf(bad1));
        var bad2 = RootModulesHelp.validateEntries(java.util.List.of("/system/app/x"));
        check("拒绝白名单外路径", bad2 != null && bad2.contains("/system"), String.valueOf(bad2));
        var bad3 = RootModulesHelp.validateEntries(java.util.List.of("etc/passwd"));
        check("拒绝相对路径", bad3 != null, String.valueOf(bad3));
        var okEmpty = RootModulesHelp.validateEntries(java.util.List.of("", "  "));
        check("空条目跳过", okEmpty == null, String.valueOf(okEmpty));

        // 10) 命令构造
        var listCmd = RootModulesHelp.buildListCommand("/x/s.tar");
        check("list 命令含 tar -tf", listCmd.equals("tar -tf /x/s.tar"), listCmd);
        var extractCmd = RootModulesHelp.buildExtractCommand("/x/s.tar");
        check("extract 命令含 -C /", extractCmd.equals("tar -xf /x/s.tar -C /"), extractCmd);
        var rcCmd = RootModulesHelp.buildRestoreconCommand();
        check("restorecon 覆盖四个目录", rcCmd.contains("modules_update") && rcCmd.contains("/data/adb/ap"), rcCmd);

        // 11) 快照清理只清 pre_restore_ 前缀
        var snap1 = new java.io.File(dir, "pre_restore_1.tar");
        var snap2 = new java.io.File(dir, "pre_restore_2.tar");
        var keepTar = new java.io.File(dir, "root_modules_keep.tar");
        Files.write(snap1.toPath(), new byte[]{1});
        Files.write(snap2.toPath(), new byte[]{1});
        Files.write(keepTar.toPath(), new byte[]{1});
        var removedSnaps = RootModulesHelp.pruneSnapshots(dir.toString(), 1);
        check("快照清理保留 1 份", removedSnaps == 1, "removed=" + removedSnaps);
        check("业务 tar 不被快照清理误删", keepTar.exists());
        check("最新快照保留", snap2.exists() && !snap1.exists());

        System.out.println("通过 " + passed + " / 失败 " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
