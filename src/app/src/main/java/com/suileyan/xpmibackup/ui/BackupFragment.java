package com.suileyan.xpmibackup.ui;

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.suileyan.cloud.CloudAccount;
import com.suileyan.cloud.CloudAccountStore;
import com.suileyan.cloud.Profile;
import com.suileyan.cloud.ProfileStore;
import com.suileyan.cloud.ProviderRegistry;
import com.suileyan.comm.ConfigHelp;
import com.suileyan.xpmibackup.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 备份页面
 * 备份方式：NAS 备份（选择已保存的方案）/ 云盘备份（选择已登录云盘，传输待开发）
 */
public class BackupFragment extends Fragment {

    private RadioGroup rgBackupMethod;
    private RadioButton rbNas, rbCloud;
    private LinearLayout panelNas, panelCloud;
    private Spinner profileSpinner, cloudSpinner;
    private Button btnStartBackup;
    private android.widget.CheckBox cbRootModules;

    private List<Profile> profiles = new ArrayList<>();
    private List<CloudAccount> cloudAccounts = new ArrayList<>();

    /**
     * 初始化界面：绑定备份方式选择与开始备份按钮
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        var t0 = System.currentTimeMillis();
        var view = inflater.inflate(R.layout.fragment_backup, container, false);

        rgBackupMethod = view.findViewById(R.id.rg_backup_method);
        rbNas = view.findViewById(R.id.rb_nas);
        rbCloud = view.findViewById(R.id.rb_cloud);
        panelNas = view.findViewById(R.id.panel_nas);
        panelCloud = view.findViewById(R.id.panel_cloud);
        profileSpinner = view.findViewById(R.id.backup_profile_spinner);
        cloudSpinner = view.findViewById(R.id.cloud_account_spinner);
        btnStartBackup = view.findViewById(R.id.btn_start_backup);
        Button btnPcBackup = view.findViewById(R.id.btn_pc_backup);

        loadProfiles();
        loadCloudAccounts(this::restoreLastState);

        // listener 必须先注册，restoreLastState 里的 setChecked 才能触发面板切换
        rgBackupMethod.setOnCheckedChangeListener((group, checkedId) -> {
            var nasSelected = checkedId == R.id.rb_nas;
            panelNas.setVisibility(nasSelected ? View.VISIBLE : View.GONE);
            panelCloud.setVisibility(nasSelected ? View.GONE : View.VISIBLE);
        });

        btnStartBackup.setOnClickListener(v -> startBackup());
        btnPcBackup.setOnClickListener(v -> showPcBackupDialog());

        // 恢复 Root 模块：从 Transfer 快照解包回 /data/adb（二期，独立确认 UI）
        Button btnRestoreModules = view.findViewById(R.id.btn_restore_modules);
        btnRestoreModules.setOnClickListener(v -> showRestoreModulesDialog());

        // Root 模块备份开关（Magisk/KernelSU/APatch，随备份打包到云端/电脑）
        cbRootModules = view.findViewById(R.id.cb_root_modules);
        cbRootModules.setChecked("on".equals(ConfigHelp.getString("root_modules_backup", "on")));
        cbRootModules.setOnCheckedChangeListener((b, isChecked) -> {
            try {
                var cfg = ConfigHelp.load();
                cfg.put("root_modules_backup", isChecked ? "on" : "off");
                ConfigHelp.save(cfg);
            } catch (Exception e) {
                com.suileyan.comm.LogHelp.w("XpMiBackup", "save root modules toggle failed", e);
            }
        });
        com.suileyan.comm.LogHelp.i("XpMiBackup", "STARTUP BackupFragment onCreateView: " + (System.currentTimeMillis() - t0) + "ms");
        return view;
    }

    /**
     * 重新加载云盘账号并恢复上次选择（常驻 Tab 页 onCreateView 只执行一次，
     * 添加新账号后由 MainActivity 切换到本 Tab 时调用）
     */
    public void refresh() {
        if (getView() == null) return;
        loadCloudAccounts(this::restoreLastState);
    }

    /**
     * 加载已保存的 NAS 配置方案到下拉，默认选中激活方案
     */
    private void loadProfiles() {
        profiles = ProfileStore.list();
        var names = new ArrayList<String>();
        for (var p : profiles) {
            names.add(p.name != null && !p.name.isEmpty() ? p.name : typeLabel(p.type));
        }
        var adapter = new ArrayAdapter<>(getActivity(), android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(adapter);

        var activeId = ProfileStore.getActiveId();
        for (var i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(activeId)) {
                profileSpinner.setSelection(i);
                break;
            }
        }
    }

    /**
     * 加载已登录云盘账号到下拉（显示「网盘名 · 脱敏账号」区分同网盘不同账户）。
     * 账号列表涉及凭据解密（PBKDF2 600000 迭代，首次约 2 秒），后台加载避免主线程阻塞（启动黑屏）；
     * 加载完成回调 onLoaded（用于恢复上次选择等依赖账号列表的逻辑）
     */
    private void loadCloudAccounts(Runnable onLoaded) {
        com.suileyan.comm.Async.run("backup-load-cloud", () -> {
            var accounts = CloudAccountStore.list();
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                // 115 网盘已撤销支持：从目标选择列表隐藏已登录的 115 账号
                var visible = new ArrayList<CloudAccount>();
                for (var a : accounts) {
                    if (com.suileyan.cloud.CloudAccount.PROVIDER_115.equals(a.provider)) continue;
                    visible.add(a);
                }
                cloudAccounts = visible;
                var names = new ArrayList<String>();
                for (var a : cloudAccounts) {
                    names.add(com.suileyan.cloud.AccountDisplay.display(a));
                }
                var adapter = new ArrayAdapter<>(getActivity(), android.R.layout.simple_spinner_item, names);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                cloudSpinner.setAdapter(adapter);
                if (onLoaded != null) onLoaded.run();
            });
        });
    }

    /**
     * 恢复上次备份方式与目标选择（记忆功能）
     * config.ini 保存 backup_method / last_profile_id / last_cloud_account_id
     */
    private void restoreLastState() {
        try {
            var cfg = com.suileyan.comm.ConfigHelp.load();
            var method = cfg.optString("backup_method", "nas");
            if ("cloud".equals(method)) {
                rbCloud.setChecked(true);
                // 显式同步面板（不依赖 setChecked 触发 listener 的时序）
                panelNas.setVisibility(View.GONE);
                panelCloud.setVisibility(View.VISIBLE);
                var lastCloud = cfg.optString("last_cloud_account_id", "");
                for (var i = 0; i < cloudAccounts.size(); i++) {
                    if (cloudAccounts.get(i).id.equals(lastCloud)) {
                        cloudSpinner.setSelection(i);
                        break;
                    }
                }
            } else {
                rbNas.setChecked(true);
                panelNas.setVisibility(View.VISIBLE);
                panelCloud.setVisibility(View.GONE);
                var lastProfile = cfg.optString("last_profile_id", "");
                for (var i = 0; i < profiles.size(); i++) {
                    if (profiles.get(i).id.equals(lastProfile)) {
                        profileSpinner.setSelection(i);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            com.suileyan.comm.LogHelp.w("XpMiBackup", "restore backup state failed", e);
        }
    }

    /**
     * 开始备份。开启 Root 模块备份时先 su 打包 /data/adb 模块目录到 Transfer/
     * （成功后宿主 hook 在备份列表注入对应条目；失败仅提示并继续普通备份），再走原流程。
     */
    private void startBackup() {
        if (cbRootModules == null || !cbRootModules.isChecked()) {
            proceedStartBackup();
            return;
        }
        Toast.makeText(getActivity(), R.string.root_modules_packing, Toast.LENGTH_SHORT).show();
        com.suileyan.comm.Async.run("root-modules-tar", () -> {
            // 本 App 可见的管理器包名 → 推断「SukiSU/KernelSU/APatch/Magisk」动态命名
            var managers = new java.util.ArrayList<String>();
            for (var pair : com.suileyan.comm.RootModulesHelp.MANAGER_PACKAGES) {
                try {
                    getActivity().getPackageManager().getPackageInfo(pair[0], 0);
                    managers.add(pair[0]);
                } catch (Exception ignored) {
                }
            }
            var err = com.suileyan.comm.RootModulesHelp.createTarViaSu(
                    ConfigHelp.BACKUP_ROOT + "/Transfer", managers);
            var activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (err != null) {
                    Toast.makeText(getActivity(),
                            getString(R.string.root_modules_pack_fail) + "\n" + err,
                            Toast.LENGTH_LONG).show();
                }
                proceedStartBackup();
            });
        });
    }

    /** 原开始备份流程：NAS 方式 / 云盘方式设置目标后跳转小米智能存储备份页 */
    private void proceedStartBackup() {
        if (rbCloud.isChecked()) {
            if (cloudAccounts.isEmpty()) {
                Toast.makeText(getActivity(), R.string.toast_no_cloud_account, Toast.LENGTH_LONG).show();
                return;
            }
            var index = cloudSpinner.getSelectedItemPosition();
            if (index < 0 || index >= cloudAccounts.size()) {
                Toast.makeText(getActivity(), R.string.toast_no_cloud_account, Toast.LENGTH_LONG).show();
                return;
            }
            var account = cloudAccounts.get(index);
            rememberState("cloud", "", account.id);
            // 云盘备份目标：ProviderRegistry 分发到该云盘账号
            ProviderRegistry.setCloudTarget(account.id);
            ProviderRegistry.invalidateAll();
            com.suileyan.comm.LogHelp.i("XpMiBackup", "backup target set to cloud account: " + account.id + " (" + account.name + ")");
            launchBackupApp();
            return;
        }
        var index = profileSpinner.getSelectedItemPosition();
        if (index < 0 || index >= profiles.size()) {
            Toast.makeText(getActivity(), R.string.toast_no_profile, Toast.LENGTH_LONG).show();
            return;
        }
        var profile = profiles.get(index);
        rememberState("nas", profile.id, "");
        ProfileStore.setActive(profile.id);
        ProviderRegistry.clearCloudTarget();
        ProviderRegistry.invalidateAll();
        launchBackupApp();
    }

    /** 持久化备份方式与目标选择（记忆功能） */
    private void rememberState(String method, String profileId, String cloudAccountId) {
        try {
            var cfg = com.suileyan.comm.ConfigHelp.load();
            cfg.put("backup_method", method);
            cfg.put("last_profile_id", profileId == null ? "" : profileId);
            cfg.put("last_cloud_account_id", cloudAccountId == null ? "" : cloudAccountId);
            com.suileyan.comm.ConfigHelp.save(cfg);
        } catch (Exception e) {
            com.suileyan.comm.LogHelp.w("XpMiBackup", "remember backup state failed", e);
        }
    }

    /** 跳转小米智能存储备份页（云盘/NAS 共用） */
    private void launchBackupApp() {
        var deviceId = ConfigHelp.getString("device_id", "");
        if (deviceId.isEmpty()) {
            Toast.makeText(getActivity(), R.string.toast_device_id_required, Toast.LENGTH_LONG).show();
            return;
        }
        var deviceName = ConfigHelp.getString("device_name", "");
        var intent = new Intent("miui.intent.backup.NAS_HOME_ACTIVITY");
        intent.putExtra("deviceId", deviceId);
        intent.putExtra("deviceName", deviceName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(getActivity(), R.string.toast_backup_app_missing, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 备份到电脑：把电脑端 mibackpc（WebDAV 接收器）配置为一条 WebDAV 方案并激活。
     * 流程：填地址/账号/密码 → 测试连接（复用真实 WebdavFileHelp，与后续备份同一链路）
     * → 保存为「电脑备份」方案（同名复用，密码入 EncryptedCredStore）→ 设为激活。
     * 之后「开始备份」走既有 NAS 流程跳转智能存储页。
     */
    /** 恢复 Root 模块：选择快照 → 二次确认 → su 解包回 /data/adb */
    private void showRestoreModulesDialog() {
        var ctx = getActivity();
        if (ctx == null) return;
        if (!"on".equals(ConfigHelp.getString("root_modules_backup", "on"))) {
            Toast.makeText(getActivity(), R.string.root_modules_backup, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(getActivity(), R.string.root_modules_packing, Toast.LENGTH_SHORT).show();
        com.suileyan.comm.Async.run("restore-list", () -> {
            var tars = com.suileyan.comm.RootModulesHelp.listTars(
                    ConfigHelp.BACKUP_ROOT + "/Transfer");
            var activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (tars.isEmpty()) {
                    Toast.makeText(getActivity(), R.string.restore_modules_none,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                var names = new String[tars.size()];
                for (var i = 0; i < tars.size(); i++) {
                    var t = tars.get(i);
                    names[i] = t.getName() + "（"
                            + android.text.format.Formatter.formatShortFileSize(ctx, t.length())
                            + "）";
                }
                var selected = new int[]{0};
                new AlertDialog.Builder(ctx)
                        .setTitle(R.string.restore_modules_title)
                        .setSingleChoiceItems(names, 0, (d, w) -> selected[0] = w)
                        .setPositiveButton(R.string.restore_modules_next, (d, w) ->
                                confirmRestoreModules(tars.get(selected[0])))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    /** 二次确认：覆盖警告 + 快照说明 + 重启提示 */
    private void confirmRestoreModules(java.io.File tar) {
        var ctx = getActivity();
        if (ctx == null) return;
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.restore_modules_title)
                .setMessage(getString(R.string.restore_modules_confirm, tar.getName()))
                .setPositiveButton(android.R.string.ok, (d, w) -> doRestoreModules(tar))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void doRestoreModules(java.io.File tar) {
        Toast.makeText(getActivity(), R.string.root_modules_packing, Toast.LENGTH_SHORT).show();
        com.suileyan.comm.Async.run("restore-modules", () -> {
            var err = com.suileyan.comm.RootModulesHelp.restoreViaSu(tar.getAbsolutePath(),
                    ConfigHelp.BACKUP_ROOT + "/Transfer");
            var activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (err == null) {
                    Toast.makeText(getActivity(), R.string.restore_modules_done,
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getActivity(),
                            getString(R.string.restore_modules_fail) + err,
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void showPcBackupDialog() {
        var ctx = getActivity();
        if (ctx == null) return;

        var addrInput = new EditText(ctx);
        addrInput.setHint(R.string.pc_backup_addr);
        addrInput.setSingleLine(true);
        addrInput.setText(ConfigHelp.getString("pc_backup_addr", ""));
        var userInput = new EditText(ctx);
        userInput.setHint(R.string.pc_backup_user);
        userInput.setSingleLine(true);
        userInput.setText(ConfigHelp.getString("pc_backup_user", ""));
        var passInput = new EditText(ctx);
        passInput.setHint(R.string.pc_backup_pass);
        passInput.setSingleLine(true);
        passInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        var box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(label(ctx, R.string.pc_backup_addr));
        box.addView(addrInput);
        box.addView(label(ctx, R.string.pc_backup_user));
        box.addView(userInput);
        box.addView(label(ctx, R.string.pc_backup_pass));
        box.addView(passInput);
        var scroll = new ScrollView(ctx);
        scroll.addView(box);

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.pc_backup_title)
                .setView(scroll)
                .setPositiveButton(R.string.pc_backup_test_save, (d, which) ->
                        testAndSavePc(addrInput.getText().toString().trim(),
                                userInput.getText().toString().trim(),
                                passInput.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 后台测试连接；成功则落盘为激活方案 */
    private void testAndSavePc(String addr, String user, String pass) {
        if (addr.isEmpty()) {
            Toast.makeText(getActivity(), R.string.pc_backup_addr_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        var url = normalizePcUrl(addr);
        if (url == null) {
            Toast.makeText(getActivity(), R.string.pc_backup_addr_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        // 记住地址/账号（非敏感），密码走 EncryptedCredStore
        try {
            var cfg = ConfigHelp.load();
            cfg.put("pc_backup_addr", addr);
            cfg.put("pc_backup_user", user);
            ConfigHelp.save(cfg);
        } catch (Exception e) {
            com.suileyan.comm.LogHelp.w("XpMiBackup", "save pc addr failed", e);
        }

        var params = new java.util.LinkedHashMap<String, String>();
        params.put("webdav_url", url);
        params.put("webdav_user", user);
        params.put("webdav_pass", pass);

        Toast.makeText(getActivity(), R.string.cred_checking, Toast.LENGTH_SHORT).show();
        com.suileyan.comm.Async.run("pc-backup-test", () -> {
            Boolean result;
            try {
                result = ConfigHelp.withAccount(params, () -> com.suileyan.comm.WebdavFileHelp.testConnection());
            } catch (Exception e) {
                com.suileyan.comm.LogHelp.w("XpMiBackup", "pc backup test failed", e);
                result = Boolean.FALSE;
            }
            final boolean ok = Boolean.TRUE.equals(result);
            var activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (ok) {
                    savePcProfile(url, user, pass);
                    Toast.makeText(getActivity(), R.string.pc_backup_ok, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getActivity(), R.string.pc_backup_fail, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /** 规范化地址 → WebDAV base URL；非法返回 null。
     *  192.168.1.3:8321 → http://…/dav/；已带 /dav(/) 的完整地址原样规范；带其它路径视为非法（PC 端固定挂载 /dav/） */
    static String normalizePcUrl(String addr) {
        var a = addr.trim();
        if (a.isEmpty()) return null;
        if (!a.contains("://")) a = "http://" + a;
        var rest = a.substring(a.indexOf("://") + 3);
        var path = rest.contains("/") ? rest.substring(rest.indexOf('/')) : "";
        if (path.isEmpty() || path.equals("/")) {
            // 必须先补齐尾斜杠再拼接：a + "dav/" 会把端口吞成 "8321dav"（真机实测）
            var base = a.endsWith("/") ? a : a + "/";
            return base + "dav/";
        }
        if (path.equals("/dav") || path.equals("/dav/")) {
            return a.endsWith("/") ? a : a + "/";
        }
        return null;
    }

    /** 保存为「电脑备份」方案（同名复用保留凭据位置）并设为激活 */
    private void savePcProfile(String url, String user, String pass) {
        var pid = java.util.UUID.randomUUID().toString();
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("webdav_url", url);
        params.put("webdav_user", user);
        var profile = new Profile(pid, "电脑备份", Profile.TYPE_WEBDAV, System.currentTimeMillis(), params);
        var saved = ProfileStore.upsertByName("电脑备份", profile);
        com.suileyan.cloud.EncryptedCredStore.put(saved.id, "webdav_pass", pass);
        ProfileStore.setActive(saved.id);
        ProviderRegistry.clearCloudTarget();
        ProviderRegistry.invalidateAll();
        rememberState("nas", saved.id, "");
        loadProfiles();
        for (var i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(saved.id)) {
                profileSpinner.setSelection(i);
                break;
            }
        }
        com.suileyan.comm.LogHelp.i("XpMiBackup", "PC backup profile saved/activated: " + saved.id);
    }

    private TextView label(android.content.Context ctx, int resId) {
        var tv = new TextView(ctx);
        tv.setText(resId);
        tv.setTextSize(12);
        tv.setTextColor(ctx.getColor(R.color.text_secondary));
        tv.setPadding(0, dp(10), 0, dp(2));
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String typeLabel(String type) {
        if (Profile.TYPE_SMB.equals(type)) return getString(R.string.account_type_smb);
        if (Profile.TYPE_WEBDAV.equals(type)) return getString(R.string.account_type_webdav);
        if (Profile.TYPE_SCRIPT.equals(type)) return getString(R.string.account_type_custom);
        return type;
    }
}
