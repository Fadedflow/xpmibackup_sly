package com.suileyan.xpmibackup.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

import com.suileyan.comm.ConfigHelp;
import com.suileyan.comm.LogHelp;
import com.suileyan.comm.RootModulesHelp;
import com.suileyan.xpmibackup.R;

import java.io.File;

/** Root 模块恢复 UI：快照选择 → 二次确认 → su 解包。备份页按钮与主界面自动提示共用。 */
public final class ModuleRestoreUi {

    private ModuleRestoreUi() {
    }

    /** 选择快照并恢复（备份页「恢复 Root 模块」按钮入口） */
    public static void pickAndRestore(Activity activity) {
        com.suileyan.comm.Async.run("restore-list", () -> {
            var tars = RootModulesHelp.listTars(RootModulesHelp.modulesDir());
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (tars.isEmpty()) {
                    Toast.makeText(activity, R.string.restore_modules_none,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                var names = new String[tars.size()];
                for (var i = 0; i < tars.size(); i++) {
                    var t = tars.get(i);
                    names[i] = t.getName() + "（"
                            + android.text.format.Formatter.formatShortFileSize(activity, t.length())
                            + "）";
                }
                var selected = new int[]{0};
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.restore_modules_title)
                        .setSingleChoiceItems(names, 0, (d, w) -> selected[0] = w)
                        .setPositiveButton(R.string.restore_modules_next, (d, w) ->
                                restore(activity, tars.get(selected[0])))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    /** 二次确认 + 执行恢复（主界面自动提示也走这里） */
    public static void restore(Activity activity, File tar) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.restore_modules_title)
                .setMessage(activity.getString(R.string.restore_modules_confirm, tar.getName()))
                .setPositiveButton(android.R.string.ok, (d, w) -> execute(activity, tar))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void execute(Activity activity, File tar) {
        Toast.makeText(activity, R.string.root_modules_packing, Toast.LENGTH_SHORT).show();
        com.suileyan.comm.Async.run("restore-modules", () -> {
            var err = RootModulesHelp.restoreViaSu(tar.getAbsolutePath(),
                    RootModulesHelp.modulesDir());
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (err == null) {
                    markSeen(activity);
                    Toast.makeText(activity, R.string.restore_modules_done,
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(activity,
                            activity.getString(R.string.restore_modules_fail) + err,
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /** 记录"已见过的最新快照指纹"：避免恢复提示反复弹出 */
    public static void markSeen(Activity activity) {
        try {
            var cfg = ConfigHelp.load();
            cfg.put("root_tar_seen", RootModulesHelp.newestTarStamp(
                    RootModulesHelp.modulesDir()));
            ConfigHelp.save(cfg);
        } catch (Exception e) {
            LogHelp.w("XpMiBackup", "mark tar seen failed: " + e.getMessage());
        }
    }
}
