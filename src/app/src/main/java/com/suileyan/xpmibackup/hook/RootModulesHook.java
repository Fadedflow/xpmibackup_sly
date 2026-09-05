package com.suileyan.xpmibackup.hook;

import android.view.View;

import java.io.File;
import android.widget.ImageView;
import android.widget.TextView;

import com.suileyan.comm.ConfigHelp;
import com.suileyan.comm.LogHelp;
import com.suileyan.comm.RootModulesHelp;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Root 模块备份项：在小米备份 App 的备份列表中注入「Root 模块」条目，
 * 使 Magisk/KernelSU/APatch 的模块目录随普通备份一并上云。
 *
 * 机制（docs/ROOT-MODULES-BACKUP-DESIGN.md，反编译证据见该文档）：
 * 1. 列表硬编码在 UsbBackupSelectFragment.i1()（L1026-1033）——post 注入
 *    GroupInfo(90) + ChildInfo(90, packageName="files_for_backup")。
 * 2. files_for_backup 通道是引擎现成的"任意文件"采集器：扫描读 SP
 *    com.miui.backup.PREF_LIST_FILES 的 files_for_backup_<type> 清单算容量
 *    （UsbBackupSelectFragment L1390），采集由 BRService（L308/2316/2474）照单拷贝——
 *    所以只需把 su 打包好的 tar 路径写进 SP，其余全链路零改动。
 * 3. 标题/图标来自 Category.d(Context,int)（未知 id 返回 {0,0}，setText(0) 会崩）——
 *    post 替换为宿主合法资源对；渲染点 DataAdapter.h 再覆写标题文案。
 *
 * 前置：模块 App「开始备份」时已 su 打包 tar 到 /sdcard/MIUI/backup/Transfer/
 * （RootModulesHelp.createTarViaSu）；tar 不存在或功能开关关闭时本条目静默缺席。
 *
 * 真实字段名（jadx f#### 为显示别名，dex 里的真名是单字母）：
 *   Category.a=条目id；GroupInfo.b=选中、f=子项列表(k()返回)；
 *   ChildInfo.a=显示名、b=图标、c=选中；BRItem.type/feature/packageName 为明文。
 */
public class RootModulesHook {

    private static final String TAG = "XpMiBackup";
    private static final String MODULE_PACKAGE = "com.suileyan.xpmibackup";
    private static final int FEATURE_ID = RootModulesHelp.FEATURE_ID;
    private static final String FEATURE_KEY = RootModulesHelp.spKey();
    private static final String SP_NAME = "com.miui.backup.PREF_LIST_FILES";
    private static final String SP_KEY = "files_for_backup_" + FEATURE_ID;

    /** 宿主合法资源对（Category.d 未知 id 返回 {0,0} 会崩，需替换） */
    private static boolean hostResResolved = false;
    private static int hostTitleRes;
    private static int hostIconRes;
    /** 组标题渲染 hook 是否命中（避免 d() 命中而 h() 未命中时标题停在替身文案） */
    private static boolean titleHookOk = false;
    private static boolean listHookOk = false;

    public void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        var cl = lpparam.classLoader;
        hookCategoryD(cl);
        hookGroupTitleRender(cl);
        hookSelectFragment(cl);
        hookPredictedSize(cl);
        hookCollectSelected(cl);
        hookSelectableGate(cl);
        hookStartButton(cl);
        hookDescriptorName(cl);
        hookServiceCollect(cl);
        hookRestoreSelect(cl);
        hookDescriptorBakFile(cl);
        hookStagingCleanup(cl);
        LogHelp.i(TAG, "RootModulesHook: installed (listHook=" + listHookOk + ", titleHook=" + titleHookOk + ")");
    }

    // ---------- 0) 预估大小：未知 key 会 wait() 死等，必须拦截 ----------
    // TransItemSizeGetter.p(String)（getPredictedMediaSize 实现）：预计算映射里没有
    // files_for_backup_90（MediaSizeGetter 只为已知类型算大小）→ while(l==null) wait()
    // 死等 → 扫描线程永久挂起、全列表卡「正在计算」。before 返回 tar 实际大小即秒出。

    /** 收集选中项回填：DataAdapter.H() 对非白名单类目（Category.g/i 仅认 1/2/4/5/6/13）
     *  会 bRItem.localFileList.clear()——feature 90 被清空导致引擎无文件可备（真机实测：
     *  单选 Root 模块项时备份无法开始）。hook 收集结果，回填 tar 路径与大小。 */
    private void hookCollectSelected(ClassLoader cl) {
        var adapter = HookCompat.findClassAny(cl, "RootModulesHook.DataAdapter",
                "数据适配器", "com.miui.backup.adapter.DataAdapter");
        if (adapter == null) return;
        java.lang.reflect.Method h = null;
        try {
            h = XposedHelpers.findMethodExactIfExists(adapter, "H");
        } catch (Throwable ignored) {
        }
        if (h == null) {
            // 名字漂移兜底：无参且返回 ArrayList 的声明方法
            for (var m : adapter.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == java.util.ArrayList.class) {
                    h = m;
                    break;
                }
            }
        }
        if (h == null) {
            LogHelp.w(TAG, "RootModulesHook.DataAdapter: 未找到选中收集方法");
            return;
        }
        try {
            de.robv.android.xposed.XposedBridge.hookMethod(h, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        // 恢复页的 H() 收集语义不同：清单来自备份集描述，
                        // 填"当前最新 tar"反而是错的——恢复侧直接跳过回填
                        if (param.thisObject.getClass().getName().contains("Restore")) return;
                        var result = (java.util.ArrayList) param.getResult();
                        int n = result == null ? -1 : result.size();
                        var tar = com.suileyan.comm.RootModulesHelp.newestTar(com.suileyan.comm.RootModulesHelp.modulesDir());
                        // 诊断：无条件打印收集规模与我们的组状态（q/b/k）
                        StringBuilder diag = new StringBuilder("H: size=").append(n);
                        try {
                            var adapterObj = param.thisObject;
                            var oField = adapterObj.getClass().getSuperclass() != null
                                    ? findField(adapterObj.getClass(), java.util.ArrayList.class) : null;
                            if (oField != null) {
                                oField.setAccessible(true);
                                var groups = (java.util.ArrayList) oField.get(adapterObj);
                                diag.append(" groups=").append(groups.size());
                                for (var g : groups) {
                                    int id = idOf(g);
                                    if (id != FEATURE_ID) continue;
                                    boolean q = (Boolean) XposedHelpers.callMethod(g, "q");
                                    var kids = childrenOf(g);
                                    boolean childB = false;
                                    long childSize = -1;
                                    if (kids != null && !kids.isEmpty()) {
                                        var c0 = kids.get(0);
                                        try { childB = XposedHelpers.getBooleanField(c0, "b"); } catch (Throwable ignored) {}
                                        try { childSize = XposedHelpers.getLongField(c0, "totalSize"); } catch (Throwable ignored) {}
                                    }
                                    diag.append(" [our] q=").append(q).append(" childB=").append(childB)
                                            .append(" childSize=").append(childSize)
                                            .append(" kids=").append(kids == null ? -1 : kids.size());
                                }
                            }
                        } catch (Throwable e) {
                            diag.append(" diagErr=").append(e.getMessage());
                        }
                        LogHelp.i(TAG, diag.toString());
                        if (result == null || result.isEmpty()) return;
                        if (tar == null) return;
                        for (var item : result) {
                            try {
                                if (XposedHelpers.getIntField(item, "feature") != FEATURE_ID) continue;
                                if (!"files_for_backup".equals(
                                        XposedHelpers.getObjectField(item, "packageName"))) continue;
                                var files = (java.util.ArrayList) XposedHelpers.getObjectField(
                                        item, "localFileList");
                                if (files == null) {
                                    files = new java.util.ArrayList();
                                    XposedHelpers.setObjectField(item, "localFileList", files);
                                }
                                if (files.isEmpty()) {
                                    files.add(RootModulesHelp.canon(tar.getAbsolutePath()));
                                    XposedHelpers.setLongField(item, "totalSize", tar.length());
                                    LogHelp.i(TAG, "RootModulesHook.H: 已回填 tar 清单 "
                                            + tar.getName() + " " + tar.length());
                                }
                            } catch (Throwable e) {
                                LogHelp.w(TAG, "RootModulesHook.H: 条目回填失败 " + e.getMessage());
                            }
                        }
                    } catch (Throwable e) {
                        LogHelp.w(TAG, "RootModulesHook.H: 回填失败 " + e.getMessage());
                    }
                }
            });
            LogHelp.i(TAG, "RootModulesHook.DataAdapter: 选中收集回填已挂 " + h.getName());
        } catch (Throwable e) {
            LogHelp.w(TAG, "RootModulesHook.DataAdapter: hook 失败 " + e.getMessage());
        }
    }

    /** 可选性门放行：BackupDataAdapter.N / RestoreDataAdapter.N 白名单 f()/h()
     *  （id 1/2/4/5/6/13）之外返回 false，绑定代码随即 setEnabled(false)+setClickable(false)
     *  ——feature 90 的勾选框在备份/恢复两个页面都被禁用。两个适配器都 hook。 */
    private void hookSelectableGate(ClassLoader cl) {
        for (var candidate : new String[][]{
                {"RootModulesHook.BackupDataAdapter", "备份列表适配器", "com.miui.backup.adapter.BackupDataAdapter"},
                {"RootModulesHook.RestoreDataAdapter", "恢复列表适配器", "com.miui.backup.adapter.RestoreDataAdapter"}}) {
            var ada = HookCompat.findClassAny(cl, candidate[0], candidate[1], candidate[2]);
            if (ada == null) continue;
            java.lang.reflect.Method n = null;
            try {
                n = XposedHelpers.findMethodExactIfExists(ada, "N",
                        XposedHelpers.findClass("com.miui.backup.bean.GroupInfo", cl));
            } catch (Throwable ignored) {
            }
            if (n == null) {
                for (var m : ada.getDeclaredMethods()) {
                    if (m.getParameterCount() == 1
                            && m.getParameterTypes()[0].getName().endsWith("GroupInfo")
                            && m.getReturnType() == boolean.class) {
                        n = m;
                        break;
                    }
                }
            }
            if (n == null) {
                LogHelp.w(TAG, "RootModulesHook." + candidate[1] + ": 未找到可选性判定方法");
                continue;
            }
            try {
                de.robv.android.xposed.XposedBridge.hookMethod(n, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (idOf(param.args[0]) != FEATURE_ID) return;
                            if (!(Boolean) param.getResult()) {
                                param.setResult(Boolean.TRUE);
                                LogHelp.i(TAG, "RootModulesHook.N: 已放行 Root 模块条目可选");
                            }
                        } catch (Throwable e) {
                            LogHelp.w(TAG, "RootModulesHook.N: 放行失败 " + e.getMessage());
                        }
                    }
                });
                LogHelp.i(TAG, "RootModulesHook." + candidate[1] + ": 可选性门已挂 " + n.getName());
            } catch (Throwable e) {
                LogHelp.w(TAG, "RootModulesHook." + candidate[1] + ": 可选性门 hook 失败 " + e.getMessage());
            }
        }
    }

    /** 开始按钮强制可用：p1() 使能条件含 f2500e（预测容量阶段标志，NAS 模式下预测流程
     *  因自定义条目不完整而可能不回调 "predict size done"）——真机实测按钮持续 disabled。
     *  条件收敛：tar 存在且开关开启时，p1() 结束后若按钮仍禁用则强制启用。
     *  勾选校验仍由 H() 把关（空收集只 dismiss 不启动），无风险。 */
    private void hookStartButton(ClassLoader cl) {
        var frag = HookCompat.findClassAny(cl, "RootModulesHook.SelectFragment",
                "备份选择页", "com.miui.backup.usb.UsbBackupSelectFragment");
        if (frag == null) return;
        java.lang.reflect.Method p1 = null;
        try {
            p1 = XposedHelpers.findMethodExactIfExists(frag, "p1");
        } catch (Throwable ignored) {
        }
        if (p1 == null) {
            LogHelp.w(TAG, "RootModulesHook.SelectFragment: 未找到按钮使能方法 p1");
            return;
        }
        try {
            de.robv.android.xposed.XposedBridge.hookMethod(p1, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (com.suileyan.comm.RootModulesHelp.newestTar(com.suileyan.comm.RootModulesHelp.modulesDir()) == null) return;
                        var btn = findButtonField(param.thisObject);
                        if (btn != null && !btn.isEnabled()) {
                            btn.setEnabled(true);
                            LogHelp.i(TAG, "RootModulesHook.p1: 已强制启用开始按钮（f2500e 预测阶段未收敛）");
                        }
                    } catch (Throwable e) {
                        LogHelp.w(TAG, "RootModulesHook.p1: 启用失败 " + e.getMessage());
                    }
                }
            });
            LogHelp.i(TAG, "RootModulesHook.SelectFragment: 开始按钮使能兜底已挂 p1");
        } catch (Throwable e) {
            LogHelp.w(TAG, "RootModulesHook.SelectFragment: p1 hook 失败 " + e.getMessage());
        }
    }

    /** 找 fragment 中的 Button 字段（该类仅声明一个 Button） */
    private android.widget.Button findButtonField(Object fragment) {
        for (var f : fragment.getClass().getDeclaredFields()) {
            if (f.getType() == android.widget.Button.class) {
                try {
                    f.setAccessible(true);
                    return (android.widget.Button) f.get(fragment);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** 找类（含父类链）中第一个声明为指定类型的字段 */
    private java.lang.reflect.Field findField(Class<?> clazz, Class<?> type) {
        for (var c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (var f : c.getDeclaredFields()) {
                if (f.getType() == type) return f;
            }
        }
        return null;
    }

    /** descript/恢复列表显示名：Utils.C 按包名+feature 查表，未知 feature 返回空串——
     *  descript.xml <displayName> 为空导致恢复列表读不出备份内容。hook 放行我们的条目。 */
    private void hookDescriptorName(ClassLoader cl) {
        var utils = HookCompat.findClassAny(cl, "RootModulesHook.Utils",
                "工具类", "com.miui.backup.Utils");
        if (utils == null) return;
        java.lang.reflect.Method c = null;
        try {
            c = XposedHelpers.findMethodExactIfExists(utils, "C",
                    android.content.Context.class, String.class, int.class);
        } catch (Throwable ignored) {
        }
        if (c == null) {
            for (var m : utils.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getReturnType() == String.class && m.getParameterCount() == 3
                        && m.getParameterTypes()[0] == android.content.Context.class
                        && m.getParameterTypes()[1] == String.class
                        && m.getParameterTypes()[2] == int.class) {
                    c = m;
                    break;
                }
            }
        }
        if (c == null) {
            LogHelp.w(TAG, "RootModulesHook.Utils: 未找到显示名查表方法 C");
            return;
        }
        try {
            de.robv.android.xposed.XposedBridge.hookMethod(c, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!"files_for_backup".equals(param.args[1])
                                || ((int) param.args[2]) != FEATURE_ID) return;
                        var cur = (String) param.getResult();
                        if (cur != null && !cur.isEmpty()) return;
                        param.setResult(resolveTitle());
                    } catch (Throwable e) {
                        LogHelp.w(TAG, "RootModulesHook.C: 显示名注入失败 " + e.getMessage());
                    }
                }
            });
            LogHelp.i(TAG, "RootModulesHook.Utils: 显示名查表 hook 已挂 " + c.getName());
        } catch (Throwable e) {
            LogHelp.w(TAG, "RootModulesHook.Utils: hook 失败 " + e.getMessage());
        }
    }

    /** 标题：管理器标记文件 → 「SukiSU 模块备份」式；缺标记回退「Root 模块」 */
    private String resolveTitle() {
        try {
            var manager = com.suileyan.comm.RootModulesHelp.readManagerName(com.suileyan.comm.RootModulesHelp.modulesDir());
            if (manager != null && !manager.isEmpty()) {
                return manager + (isZh() ? " 模块备份" : " modules backup");
            }
        } catch (Throwable ignored) {
        }
        return isZh() ? "Root 模块" : "Root modules";
    }

    /** 服务侧采集兜底：BRService.u4 对 files_for_backup 条目从 SP 回填清单，但 key 取
     *  bRItem.type、且仅在 localFileList 为空时填充——真机实测 tar 未进清单（bakFileSize=0）。
     *  u4 后强制补齐，保证传输循环（new File(localFileList.get(i))) 拿到 tar。 */
    private void hookServiceCollect(ClassLoader cl) {
        var service = HookCompat.findClassAny(cl, "RootModulesHook.BRService",
                "备份服务", "com.miui.backup.service.BRService");
        if (service == null) return;
        java.lang.reflect.Method u4 = null;
        try {
            u4 = XposedHelpers.findMethodExactIfExists(service, "u4", boolean.class);
        } catch (Throwable ignored) {
        }
        if (u4 == null) {
            for (var m : service.getDeclaredMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == boolean.class
                        && m.getReturnType() == void.class) {
                    u4 = m;
                    break;
                }
            }
        }
        if (u4 == null) {
            LogHelp.w(TAG, "RootModulesHook.BRService: 未找到清单回填方法 u4");
            return;
        }
        try {
            de.robv.android.xposed.XposedBridge.hookMethod(u4, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        var tar = com.suileyan.comm.RootModulesHelp.newestTar(com.suileyan.comm.RootModulesHelp.modulesDir());
                        if (tar == null) return;
                        var items = (java.util.ArrayList) XposedHelpers.getObjectField(
                                param.thisObject, "m");
                        if (items == null) return;
                        for (var item : items) {
                            try {
                                if (!"files_for_backup".equals(
                                        XposedHelpers.getObjectField(item, "packageName"))) continue;
                                var files = (java.util.ArrayList) XposedHelpers.getObjectField(
                                        item, "localFileList");
                                if (files == null) {
                                    files = new java.util.ArrayList();
                                    XposedHelpers.setObjectField(item, "localFileList", files);
                                }
                                if (files.isEmpty()) {
                                    files.add(RootModulesHelp.canon(tar.getAbsolutePath()));
                                    XposedHelpers.setLongField(item, "totalSize", tar.length());
                                    LogHelp.i(TAG, "RootModulesHook.u4: 已补齐 tar 清单 "
                                            + tar.getName());
                                }
                            } catch (Throwable e) {
                                LogHelp.w(TAG, "RootModulesHook.u4: 条目补齐失败 " + e.getMessage());
                            }
                        }
                    } catch (Throwable e) {
                        LogHelp.w(TAG, "RootModulesHook.u4: 补齐失败 " + e.getMessage());
                    }
                }
            });
            LogHelp.i(TAG, "RootModulesHook.BRService: 服务侧清单兜底已挂 " + u4.getName());
        } catch (Throwable e) {
            LogHelp.w(TAG, "RootModulesHook.BRService: u4 hook 失败 " + e.getMessage());
        }
    }

    /** 恢复详情页条目注入：NASRestoreSelectActivity.o1(descriptor) 按 feature 5/6/7/8
     *  路由 files_for_backup 条目，feature=90 落空被丢弃 → 恢复详情页空白。
     *  o1 后把我们的 ChildInfo 包装为 GroupInfo(90) 追加进组列表
     *  （渲染/勾选/收集由已挂的 Category.d、DataAdapter.Q、N 门、H 回填统一覆盖）。 */
    private void hookRestoreSelect(ClassLoader cl) {
        var activity = HookCompat.findClassAny(cl, "RootModulesHook.NASRestoreSelectActivity",
                "恢复详情页", "com.miui.backup.nas.NASRestoreSelectActivity");
        if (activity == null) return;
        java.lang.reflect.Method o1 = null;
        try {
            o1 = XposedHelpers.findMethodExactIfExists(activity, "o1", java.util.ArrayList.class);
        } catch (Throwable ignored) {
        }
        if (o1 == null) {
            LogHelp.w(TAG, "RootModulesHook.RestoreSelect: 未找到条目构建方法 o1");
            return;
        }
        try {
            de.robv.android.xposed.XposedBridge.hookMethod(o1, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        var groups = (java.util.ArrayList) param.args[0];
                        if (groups == null) return;
                        for (var g : groups) {
                            if (idOf(g) == FEATURE_ID) return; // 幂等
                        }
                        var activityObj = param.thisObject;
                        var descriptor = XposedHelpers.getObjectField(activityObj, "H");
                        if (descriptor == null) return;
                        var packages = (java.util.ArrayList) XposedHelpers.getObjectField(
                                descriptor, "packages");
                        if (packages == null) return;
                        Object pkg = null;
                        for (var p : packages) {
                            if (p != null && "files_for_backup".equals(
                                    XposedHelpers.getObjectField(p, "packageName"))) {
                                var f = XposedHelpers.getIntField(p, "feature");
                                if (f == FEATURE_ID) {
                                    pkg = p;
                                    break;
                                }
                            }
                        }
                        if (pkg == null) return;
                        var ctx = (android.content.Context) activityObj;
                        var clz = XposedHelpers.findClass("com.miui.backup.bean.GroupInfo",
                                activityObj.getClass().getClassLoader());
                        var childClz = XposedHelpers.findClass("com.miui.backup.bean.ChildInfo",
                                activityObj.getClass().getClassLoader());
                        var group = XposedHelpers.newInstance(clz, FEATURE_ID,
                                new java.util.ArrayList());
                        var child = XposedHelpers.newInstance(childClz, FEATURE_ID);
                        XposedHelpers.setIntField(child, "feature", FEATURE_ID);
                        XposedHelpers.setObjectField(child, "packageName", "files_for_backup");
                        setFirstDeclaredOfType(childClz, child, String.class, resolveTitle());
                        setFirstDeclaredOfType(childClz, child, boolean.class, true);
                        var title = resolveTitle();
                        XposedHelpers.setLongField(child, "totalSize",
                                XposedHelpers.getLongField(pkg, "bakFileSize"));
                        // 备份集内的 tar 路径（引擎恢复时按此下载/解包）
                        var bakFiles = (java.util.ArrayList) XposedHelpers.getObjectField(
                                pkg, "bakFiles");
                        var files = new java.util.ArrayList(bakFiles == null
                                ? java.util.List.of() : bakFiles);
                        XposedHelpers.setObjectField(child, "localFileList", files);
                        // bakFilePath = 恢复暂存目录 + backup_<R>.zip（引擎从目标下载 zip 后
                        // 解出 localFileList 中的条目）；R("files_for_backup", 90) 对未知返回 ""，
                        // 即 backup_.zip——与备份端 zip 命名一致
                        var bakFilePath = XposedHelpers.getObjectField(descriptor, "path")
                                + java.io.File.separator + "backup_.zip";
                        XposedHelpers.setObjectField(child, "bakFilePath", bakFilePath);
                        var children = childrenOf(group);
                        if (children == null) return;
                        children.add(child);
                        groups.add(group);
                        LogHelp.i(TAG, "RootModulesHook.RestoreSelect: 已注入恢复条目 title="
                                + title + " files=" + files.size());
                    } catch (Throwable e) {
                        LogHelp.w(TAG, "RootModulesHook.RestoreSelect: 注入失败 " + e.getMessage());
                    }
                }
            });
            LogHelp.i(TAG, "RootModulesHook.RestoreSelect: 恢复条目注入已挂 o1");
        } catch (Throwable e) {
            LogHelp.w(TAG, "RootModulesHook.RestoreSelect: hook 失败 " + e.getMessage());
        }
    }

    /** descript bakFile 名修正：H0 对 files_for_backup 条目把 localFileList 的【绝对路径】
     *  原样写入 <bakFile>，而原生条目写裸文件名（zip 条目名）——恢复校验按 bakFile 名
     *  找文件，绝对路径找不到 → error=7「备份文件损坏」（真机实测）。
     *  pre 临时换成裸名，post 还原（运行期其它逻辑仍需要完整路径）。 */
    private void hookDescriptorBakFile(ClassLoader cl) {
        var utils = HookCompat.findClassAny(cl, "RootModulesHook.Utils",
                "工具类", "com.miui.backup.Utils");
        if (utils == null) return;
        java.lang.reflect.Method h0 = null;
        try {
            h0 = XposedHelpers.findMethodExactIfExists(utils, "H0",
                    android.content.Context.class,
                    org.xmlpull.v1.XmlSerializer.class,
                    XposedHelpers.findClass("com.miui.backup.service.BRItem", cl),
                    boolean.class);
        } catch (Throwable ignored) {
        }
        if (h0 == null) {
            LogHelp.w(TAG, "RootModulesHook.Utils: 未找到 descript 写入方法 H0");
            return;
        }
        try {
            de.robv.android.xposed.XposedBridge.hookMethod(h0, new XC_MethodHook() {
                private final java.util.Map<Object, java.util.ArrayList> saved = new java.util.HashMap<>();

                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        var item = param.args[2];
                        if (!"files_for_backup".equals(
                                XposedHelpers.getObjectField(item, "packageName"))) return;
                        var files = (java.util.ArrayList) XposedHelpers.getObjectField(
                                item, "localFileList");
                        if (files == null || files.isEmpty()) return;
                        saved.put(item, new java.util.ArrayList(files));
                        var bare = new java.util.ArrayList();
                        for (var p : files) {
                            bare.add(new java.io.File((String) p).getName());
                        }
                        XposedHelpers.setObjectField(item, "localFileList", bare);
                    } catch (Throwable e) {
                        LogHelp.w(TAG, "RootModulesHook.H0: pre 失败 " + e.getMessage());
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        var item = param.args[2];
                        var original = saved.remove(item);
                        if (original != null) {
                            XposedHelpers.setObjectField(item, "localFileList", original);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            LogHelp.i(TAG, "RootModulesHook.Utils: descript bakFile 名修正已挂 H0");
        } catch (Throwable e) {
            LogHelp.w(TAG, "RootModulesHook.Utils: H0 hook 失败 " + e.getMessage());
        }
    }


    /** 暂存抢救：原生恢复把 backup_.zip 下载到 AllBackupTemp 暂存区、解出 tar 后，
     *  H0() 会清空暂存目录——tar 随之丢失，模块 App 的指纹检测永远抓不到。
     *  hook 清理方法（pre）：把暂存区的模块 tar/zip 抢救到 RootModules/，
     *  App 侧指纹变化 → 自动弹恢复提示。按文件大小去重（应用内备份刚生成的
     *  同大小 tar 已在 RootModules/，不重复抢救、不误弹）。 */
    private void hookStagingCleanup(ClassLoader cl) {
        var svc = HookCompat.findClassAny(cl, "RootModulesHook.NASTransferService",
                "NAS 传输服务", "com.miui.backup.nas.NASTransferService");
        if (svc == null) return;
        java.lang.reflect.Method h0 = null;
        try {
            h0 = XposedHelpers.findMethodExactIfExists(svc, "H0");
        } catch (Throwable ignored) {
        }
        if (h0 == null) {
            for (var m : svc.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == void.class) {
                    // 无参 void 方法可能多个，仅在方法体内引用过暂存路径时才可放心；
                    // 保守起见只认名字 H0，漂移时打日志人工跟进
                    continue;
                }
            }
            LogHelp.w(TAG, "RootModulesHook.NASTransferService: 未找到暂存清理方法 H0");
            return;
        }
        try {
            de.robv.android.xposed.XposedBridge.hookMethod(h0, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        rescueStaging();
                    } catch (Throwable e) {
                        LogHelp.w(TAG, "暂存抢救失败 " + e.getMessage());
                    }
                }
            });
            LogHelp.i(TAG, "RootModulesHook.NASTransferService: 暂存抢救已挂 H0");
        } catch (Throwable e) {
            LogHelp.w(TAG, "RootModulesHook.NASTransferService: H0 hook 失败 " + e.getMessage());
        }
    }

    /** 扫描 AllBackupTemp 暂存区，把模块 tar/zip 抢救到 RootModules/ */
    private void rescueStaging() {
        var base = new File(com.suileyan.comm.ConfigHelp.BACKUP_ROOT + "/AllBackupTemp/miback");
        if (!base.isDirectory()) return;
        var destDir = new File(com.suileyan.comm.RootModulesHelp.modulesDir());
        if (!destDir.isDirectory() && !destDir.mkdirs()) return;
        var candidates = new java.util.ArrayList<File>();
        candidates.add(base);
        var subs = base.listFiles();
        if (subs != null) {
            for (var d : subs) {
                if (d.isDirectory()) candidates.add(d);
            }
        }
        var rescued = 0;
        for (var dir : candidates) {
            var files = dir.listFiles();
            if (files == null) continue;
            for (var f : files) {
                if (!f.isFile()) continue;
                var name = f.getName();
                if (name.endsWith(".tar") && name.startsWith("root_modules_")) {
                    // 全局按大小去重：已有同字节大小 tar 即为同一状态快照，不重复落盘
                    if (com.suileyan.comm.RootModulesHelp.hasSameSizeTar(
                            destDir.getAbsolutePath(), f.length())) {
                        continue;
                    }
                    if (copyIfNew(f, destDir, f.getName())) rescued++;
                } else if (name.startsWith("backup_") && name.endsWith(".zip")) {
                    if (extractRootModulesTar(f, destDir)) rescued++;
                }
            }
        }
        if (rescued > 0) {
            LogHelp.i(TAG, "暂存抢救: 已保存 " + rescued + " 个模块快照到 RootModules/");
        }
        // 抢救后统一收敛，防暂存多份 zip 反复抢救撑爆存储
        com.suileyan.comm.RootModulesHelp.pruneAll(destDir.getAbsolutePath());
    }

    /** 同大小文件已存在则跳过（应用内备份刚生成的 tar 已在 RootModules/） */
    private boolean copyIfNew(File src, File destDir, String targetName) {
        var target = new File(destDir, targetName);
        if (target.exists() && target.length() == src.length()) return false;
        try {
            java.nio.file.Files.copy(src.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            LogHelp.w(TAG, "抢救复制失败 " + src.getName() + ": " + e.getMessage());
            return false;
        }
    }

    /** 从备份 zip 中解出 root_modules tar，保存为 root_modules_restored_*.tar */
    private boolean extractRootModulesTar(File zip, File destDir) {
        try (var zf = new java.util.zip.ZipFile(zip)) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var en = entry.getName();
                if (en.startsWith("root_modules_") && en.endsWith(".tar")) {
                    var baseName = en.substring(en.lastIndexOf('/') + 1);
                    var marker = baseName.substring("root_modules_".length(),
                            baseName.length() - ".tar".length());
                    var target = new File(destDir, "root_modules_restored_" + marker + ".tar");
                    if (target.exists() && target.length() == entry.getSize()) return false;
                    // 全局按大小去重：已有同字节大小 tar 即不重复解出
                    if (com.suileyan.comm.RootModulesHelp.hasSameSizeTar(
                            destDir.getAbsolutePath(), entry.getSize())) {
                        return false;
                    }
                    try (var in = zf.getInputStream(entry)) {
                        java.nio.file.Files.copy(in, target.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    return true;
                }
            }
        } catch (Exception e) {
            LogHelp.w(TAG, "zip 解出 tar 失败 " + zip.getName() + ": " + e.getMessage());
        }
        return false;
    }

    private void hookPredictedSize(ClassLoader cl) {        var getter = HookCompat.findClassAny(cl, "RootModulesHook.TransItemSizeGetter",
                "容量预估器", "com.miui.backup.service.TransItemSizeGetter");
        if (getter == null) return;
        var interceptor = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!FEATURE_KEY.equals(param.args[0])) return;
                    var tar = com.suileyan.comm.RootModulesHelp.newestTar(com.suileyan.comm.RootModulesHelp.modulesDir());
                    param.setResult(tar != null ? tar.length() : 0L);
                } catch (Throwable e) {
                    LogHelp.w(TAG, "RootModulesHook: 预估大小拦截失败 " + e.getMessage());
                }
            }
        };
        // 外层委托 f/g/h/i 一一对应 getPredictedData/Media/Apk/Sd 四个查询；
        // getPredictedMediaSize 走 i → MediaSizeGetter.p（内部 wait() 死等未知 key）。
        // findMethodByParams 只会命中第一个 long(String)（真机实测命中了错误的 f），
        // 所以四个委托 + MediaSizeGetter.p 全部 hook，拦截器只对我们的 key 生效。
        var hooked = 0;
        for (var name : new String[]{"f", "g", "h", "i"}) {
            try {
                var m = XposedHelpers.findMethodExactIfExists(getter, name, String.class);
                if (m == null) continue;
                de.robv.android.xposed.XposedBridge.hookMethod(m, interceptor);
                hooked++;
            } catch (Throwable ignored) {
            }
        }
        var media = HookCompat.findClassAny(cl, "RootModulesHook.MediaSizeGetter",
                "媒体容量计算器", "com.miui.backup.service.TransItemSizeGetter$MediaSizeGetter");
        if (media != null) {
            var mm = HookCompat.findMethodByParams(media, long.class, String.class);
            if (mm != null) {
                try {
                    de.robv.android.xposed.XposedBridge.hookMethod(mm, interceptor);
                    hooked++;
                } catch (Throwable ignored) {
                }
            }
        }
        LogHelp.i(TAG, "RootModulesHook.SizeGetter: 预估大小拦截已挂 " + hooked + " 处");
        if (hooked == 0) {
            LogHelp.w(TAG, "RootModulesHook.SizeGetter.p: 全部未命中（条目容量将无法计算且扫描可能挂起）");
        }
    }

    // ---------- 1) Category.d：未知 id 的 {0,0} 会崩，替换为宿主合法资源对 ----------

    private void hookCategoryD(ClassLoader cl) {
        var category = HookCompat.findClassAny(cl, "RootModulesHook.Category",
                "条目标题/图标映射", "com.miui.backup.bean.Category");
        if (category == null) return;
        if (!resolveHostRes()) return;
        // 真机实测方法名 d 已漂移（新版宿主未命中），改按「(Context,int)→int[]」参数特征定位
        var mapper = HookCompat.findMethodByParams(category, int[].class,
                android.content.Context.class, int.class);
        if (mapper == null) {
            LogHelp.w(TAG, "RootModulesHook.Category: 未找到 (Context,int)→int[] 映射方法");
            hostResResolved = false;
            return;
        }
        try {
            de.robv.android.xposed.XposedBridge.hookMethod(mapper, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if ((int) param.args[1] != FEATURE_ID) return;
                    param.setResult(new int[]{hostTitleRes, hostIconRes});
                }
            });
            LogHelp.i(TAG, "RootModulesHook.Category: 映射方法命中 " + mapper.getName());
        } catch (Throwable e) {
            LogHelp.w(TAG, "RootModulesHook.Category: 映射 hook 失败 " + e.getMessage());
            hostResResolved = false;
        }
    }

    /** 解析占位资源对：Category.d(90) 未 hook 时返回 {0,0}，setText(0) 会崩，必须给合法 id。
     *  用 framework 公共资源（android.R）——任何进程可解析，不依赖会跨版本漂移的宿主 R 类字段名
     *  （真机实测 new_briten_other 在新版宿主不存在）；标题文案由 DataAdapter 渲染 hook 覆写。 */
    private boolean resolveHostRes() {
        hostTitleRes = android.R.string.ok;
        hostIconRes = android.R.drawable.ic_menu_gallery;
        hostResResolved = true;
        return true;
    }

    // ---------- 2) DataAdapter.h：组行渲染后覆写标题 ----------

    private void hookGroupTitleRender(ClassLoader cl) {
        var adapter = HookCompat.findClassAny(cl, "RootModulesHook.DataAdapter",
                "备份列表适配器基类", "com.miui.backup.adapter.DataAdapter");
        var holder = HookCompat.findClassAny(cl, "RootModulesHook.GroupInfoHolder",
                "组行 ViewHolder", "com.miui.backup.adapter.GroupInfoHolder");
        var expandable = HookCompat.findClassAny(cl, "RootModulesHook.ExpandableGroup",
                "组模型接口", "com.miui.backup.recyclerview.models.ExpandableGroup");
        if (adapter == null || holder == null || expandable == null) return;
        var m = HookCompat.findMethodByParams(adapter, void.class, holder, int.class, expandable);
        if (m == null) {
            LogHelp.w(TAG, "RootModulesHook.DataAdapter: 组渲染方法未命中（参数特征漂移？）");
            return;
        }
        try {
            de.robv.android.xposed.XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        var group = param.args[2];
                        if (group == null || XposedHelpers.getIntField(group, "a") != FEATURE_ID) return;
                        var holderObj = (View) XposedHelpers.getObjectField(param.args[0], "itemView");
                        var ctx = holderObj.getContext();
                        var title = (TextView) holderObj.findViewById(hostTitleViewId(ctx));
                        if (title != null) {
                            title.setText(groupTitle(ctx));
                        }
                        var icon = (ImageView) holderObj.findViewById(hostIconViewId(ctx));
                        var d = entryIcon(ctx);
                        if (icon != null && d != null) {
                            cancelGlideAndSet(icon, d, ctx);
                        }
                    } catch (Throwable e) {
                        LogHelp.w(TAG, "RootModulesHook: 组标题覆写失败 " + e.getMessage());
                    }
                }
            });
            titleHookOk = true;
            LogHelp.i(TAG, "RootModulesHook.DataAdapter: 组渲染覆写命中 " + m.getDeclaringClass().getName()
                    + "." + m.getName());
        } catch (Throwable e) {
            LogHelp.w(TAG, "RootModulesHook.DataAdapter: hook 失败 " + e.getMessage());
        }
    }

    // ---------- 3) 备份列表注入 ----------
    // 不 hook 列表构建方法（i1 等混淆名跨版本漂移，真机已实测未命中），
    // 而是 hook BackupDataAdapter 构造函数：列表页创建适配器时必传入 GroupInfo 列表（this.m），
    // 构造参数稳定性远高于混淆方法名。

    private void hookSelectFragment(ClassLoader cl) {
        var adapterClass = HookCompat.findClassAny(cl, "RootModulesHook.BackupDataAdapter",
                "备份列表适配器", "com.miui.backup.adapter.BackupDataAdapter");
        if (adapterClass == null) return;
        try {
            de.robv.android.xposed.XposedBridge.hookAllConstructors(adapterClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        android.content.Context ctx = null;
                        java.util.ArrayList list = null;
                        for (var arg : param.args) {
                            if (arg instanceof android.content.Context c) ctx = c;
                            else if (arg instanceof java.util.ArrayList l) list = l;
                        }
                        if (ctx == null || list == null) return;
                        injectRootModulesItem(param.thisObject, ctx, list);
                    } catch (Throwable e) {
                        LogHelp.w(TAG, "RootModulesHook: 条目注入失败 " + e.getMessage());
                    }
                }
            });
            listHookOk = true;
            LogHelp.i(TAG, "RootModulesHook.BackupDataAdapter: 构造函数 hook 命中");
        } catch (Throwable e) {
            LogHelp.w(TAG, "RootModulesHook.BackupDataAdapter: 构造函数 hook 失败 " + e.getMessage());
        }
    }

    /** 适配器构造完成后追加「Root 模块」组（tar 缺失/开关关闭/前置 hook 未命中时静默缺席）。
     *  关键：DataAdapter 构造函数把传入列表【复制】到自身字段 o——渲染走父类持有的原列表，
     *  收集 H() 走 o。只注入传入列表会出现「看得到、勾得了、备份收集不到」
     *  （真机实测 H: size=0 groups=6），必须两个列表都注入。 */
    private void injectRootModulesItem(Object adapter, android.content.Context ctx, java.util.ArrayList list) {
        if (!hostResResolved || !titleHookOk) return;
        if (!"on".equals(ConfigHelp.getString("root_modules_backup", "on"))) return;

        var tar = com.suileyan.comm.RootModulesHelp.newestTar(com.suileyan.comm.RootModulesHelp.modulesDir());
        if (tar == null) {
            LogHelp.i(TAG, "RootModulesHook: 无 root_modules_*.tar，跳过注入");
            return;
        }
        // 幂等：列表重建会再次走构造，先查重
        for (var o : list) {
            var id = idOf(o);
            if (id == FEATURE_ID) return;
            if (id == Integer.MIN_VALUE) continue; // 非 Category 条目
        }

        var cl = ctx.getClassLoader();
        var groupInfoClass = XposedHelpers.findClass("com.miui.backup.bean.GroupInfo", cl);
        var childInfoClass = XposedHelpers.findClass("com.miui.backup.bean.ChildInfo", cl);

        // 查重：已存在则先移除，统一全新注入（tar 大小可能变化）
        list.removeIf(o -> idOf(o) == FEATURE_ID);

        var group = XposedHelpers.newInstance(groupInfoClass, FEATURE_ID, new java.util.ArrayList());
        // 关键：b 是「计算中」标志（渲染 if(b) 显示"正在计算"），扫描完成由引擎清 false。
        // 我们自带 localFileList/totalSize 不参与扫描，b 必须保持默认 false——
        // 置 true 会因无人清除而永久显示"正在计算"（真机实测）。
        // d = 容量已算完标志，置 true 防渲染/勾选逻辑重置子项勾选态。
        trySetByName(group, "d", true);
        var child = XposedHelpers.newInstance(childInfoClass, FEATURE_ID);
        XposedHelpers.setIntField(child, "feature", FEATURE_ID);
        XposedHelpers.setObjectField(child, "packageName", "files_for_backup");
        setFirstDeclaredOfType(childInfoClass, child, String.class, groupTitle(ctx));
        setFirstDeclaredOfType(childInfoClass, child, boolean.class, true);
        var icon = entryIcon(ctx);
        if (icon != null) {
            setFirstDeclaredOfType(childInfoClass, child, android.graphics.drawable.Drawable.class, icon);
        }
        // 自带文件清单与大小：不依赖扫描（扫描若重跑，也会按 SP 清单重新填充，两者一致）
        XposedHelpers.setLongField(child, "totalSize", tar.length());
        var fileList = new java.util.ArrayList<String>();
        fileList.add(RootModulesHelp.canon(tar.getAbsolutePath()));
        XposedHelpers.setObjectField(child, "localFileList", fileList);
        var children = childrenOf(group);
        if (children == null) {
            LogHelp.w(TAG, "RootModulesHook: 子项列表未命中");
            return;
        }
        children.add(child);
        list.add(group);
        // 收集列表：adapter 自身字段 o（H() 遍历它）——构造函数已把传入列表复制进 o，
        // 必须再向 o 补一份，否则渲染可见而收集为空
        try {
            var oList = (java.util.ArrayList) XposedHelpers.getObjectField(adapter, "o");
            if (oList != null && oList != list) {
                oList.add(group);
            }
        } catch (Throwable e) {
            LogHelp.w(TAG, "RootModulesHook: 注入收集列表失败 " + e.getMessage());
        }

        // files_for_backup_90 清单 → 引擎据此算容量与采集
        // MODE_MULTI_PROCESS：与引擎 BigDataTranser 读取侧一致的打开模式
        ctx.getSharedPreferences(SP_NAME, android.content.Context.MODE_MULTI_PROCESS)
                .edit()
                .putString(SP_KEY, RootModulesHelp.buildFileListJson(RootModulesHelp.canon(tar.getAbsolutePath()), tar.length()))
                .apply();
        LogHelp.i(TAG, "RootModulesHook: 已注入条目 tar=" + tar.getName()
                + " title=" + groupTitle(ctx));
    }

    // ---------- 资源辅助 ----------

    /** 宿主 R$id.title（组行标题控件，GroupInfoHolder 同源 findViewById） */
    private int hostTitleViewId(android.content.Context ctx) {
        return ctx.getResources().getIdentifier("title", "id", ctx.getPackageName());
    }

    private int hostIconViewId(android.content.Context ctx) {
        return ctx.getResources().getIdentifier("icon", "id", ctx.getPackageName());
    }

    /** 组标题：读管理器名单标记 → 「XX 模块备份」；缺失回退「Root 模块」（i18n） */
    private String groupTitle(android.content.Context ctx) {
        try {
            var res = moduleResources(ctx);
            var id = res.getIdentifier("root_modules_title", "string", MODULE_PACKAGE);
            var fallback = id != 0 ? res.getString(id) : (isZh() ? "Root 模块" : "Root modules");
            var name = com.suileyan.comm.RootModulesHelp.readManagerName(com.suileyan.comm.RootModulesHelp.modulesDir());
            if (name != null) {
                return isZh() ? name + " 模块备份" : name + " modules";
            }
            return fallback;
        } catch (Throwable e) {
            return isZh() ? "Root 模块" : "Root modules";
        }
    }

    /** 模块 APK 内的条目图标（VectorDrawable），失败返回 null（保留 Glide 兜底图标） */

    /** 取消 Glide 对该 ImageView 的挂起异步请求并设置图标。
     *  引擎渲染用 Glide 异步加载组图标，我们的同步 setImageDrawable 会被稍后到达的
     *  Glide 结果覆盖（真机实测"图标时有时无"）；clear 后再设置即确定性生效。 */
    private void cancelGlideAndSet(ImageView iv, android.graphics.drawable.Drawable d,
                                   android.content.Context ctx) {
        try {
            var glide = XposedHelpers.findClass("com.bumptech.glide.Glide", ctx.getClassLoader());
            // 显式按参数类型匹配：with(ImageView) 依赖重载推断在部分 XposedHelpers
            // 实现下匹配不到 with(View)/with(Context)（真机日志验证），导致 clear
            // 从未生效、一直走回退路径（时间竞争未消除）
            var withCtx = XposedHelpers.findMethodBestMatch(glide, "with",
                    new Class[]{android.content.Context.class}, ctx);
            var requestManager = withCtx.invoke(null, ctx);
            var clearView = XposedHelpers.findMethodBestMatch(requestManager.getClass(), "clear",
                    new Class[]{android.view.View.class}, iv);
            clearView.invoke(requestManager, iv);
        } catch (Throwable e) {
            LogHelp.w(TAG, "Glide clear 失败（回退直接设置）" + e.getMessage());
        }
        iv.setImageDrawable(d);
    }

    /** 条目图标：优先当前 Root 管理器的应用图标（标记文件携带包名），回退模块内置图标 */
    private android.graphics.drawable.Drawable entryIcon(android.content.Context ctx) {
        try {
            var pkg = com.suileyan.comm.RootModulesHelp.readManagerPackage(com.suileyan.comm.RootModulesHelp.modulesDir());
            if (pkg != null && !pkg.isEmpty()) {
                var d = ctx.getPackageManager().getApplicationIcon(pkg);
                if (d != null) return d;
            }
        } catch (Throwable ignored) {
        }
        return moduleIcon(ctx);
    }

    private android.graphics.drawable.Drawable moduleIcon(android.content.Context ctx) {
        try {
            var res = moduleResources(ctx);
            var id = res.getIdentifier("ic_root_modules", "drawable", MODULE_PACKAGE);
            if (id != 0) return res.getDrawable(id);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private android.content.res.Resources moduleResources(android.content.Context ctx)
            throws android.content.pm.PackageManager.NameNotFoundException {
        var moduleCtx = ctx.createPackageContext(MODULE_PACKAGE,
                android.content.Context.CONTEXT_IGNORE_SECURITY);
        return moduleCtx.getResources();
    }

    private boolean isZh() {
        return "zh".equalsIgnoreCase(java.util.Locale.getDefault().getLanguage());
    }

    // ---------- 抗漂移字段/方法访问 ----------
    // 反编译版字段名（jadx 显示的 a/b/c/f 与 f#### 别名）跨备份 App 版本会漂移，
    // 且各字段漂移不一致（真机实测 ChildInfo.c 缺失而 a 存在）。策略：
    //   ① 名字候选优先（命中当前反编译版的已知名）；② 失败按「类型在类内唯一」命中。
    // 新版备份 App 反编译后如仍不命中，把新字段名补进候选即可。

    /** 读条目 id（Category.a 候选名失败回退：该类仅声明一个 int 字段）；非目标类返回 MIN_VALUE */
    private static int idOf(Object group) {
        try {
            return XposedHelpers.getIntField(group, "a");
        } catch (Throwable ignored) {
        }
        try {
            for (var f : group.getClass().getDeclaredFields()) {
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    return f.getInt(group);
                }
            }
        } catch (Throwable ignored) {
        }
        return Integer.MIN_VALUE;
    }

    /** 按名字设置字段；失败返回 false（调用方决定类型回退） */
    private static boolean trySetByName(Object target, String name, Object value) {
        try {
            XposedHelpers.setObjectField(target, name, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 设置类中第一个匹配类型的声明字段（类型在类内唯一时才安全，调用方需保证） */
    private static boolean setFirstDeclaredOfType(Class<?> clazz, Object target, Class<?> type, Object value) {
        for (var f : clazz.getDeclaredFields()) {
            if (f.getType() == type) {
                try {
                    f.setAccessible(true);
                    f.set(target, value);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        }
        LogHelp.w(TAG, "RootModulesHook: 类型字段未命中 " + clazz.getSimpleName() + "#" + type.getSimpleName());
        return false;
    }

    /** 子项列表：GroupInfo.k() 候选名失败回退首个声明的 ArrayList 字段（该类仅一个） */
    private static java.util.ArrayList childrenOf(Object group) {
        try {
            var r = XposedHelpers.callMethod(group, "k");
            if (r instanceof java.util.ArrayList l) return l;
        } catch (Throwable ignored) {
        }
        try {
            for (var f : group.getClass().getDeclaredFields()) {
                if (f.getType() == java.util.ArrayList.class) {
                    f.setAccessible(true);
                    return (java.util.ArrayList) f.get(group);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
