package android.util;

/**
 * 桌面 JVM 测试台桩：替代 android.jar 中的 Stub 实现（android.jar 的 Log 在桌面 JVM 会抛 "Stub!"）。
 * 常量与重载按 API 37 android.util.Log 的公开面补齐，仅用于 pc/harness，不参与 Android 构建。
 */
public class Log {
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    public static int v(String tag, String msg) { return println('V', tag, msg); }
    public static int v(String tag, String msg, Throwable t) { return println('V', tag, msg + " | " + t); }
    public static int d(String tag, String msg) { return println('D', tag, msg); }
    public static int d(String tag, String msg, Throwable t) { return println('D', tag, msg + " | " + t); }
    public static int i(String tag, String msg) { return println('I', tag, msg); }
    public static int i(String tag, String msg, Throwable t) { return println('I', tag, msg + " | " + t); }
    public static int w(String tag, String msg) { return println('W', tag, msg); }
    public static int w(String tag, String msg, Throwable t) { return println('W', tag, msg + " | " + t); }
    public static int w(String tag, Throwable t) { return println('W', tag, "" + t); }
    public static int e(String tag, String msg) { return println('E', tag, msg); }
    public static int e(String tag, String msg, Throwable t) { return println('E', tag, msg + " | " + t); }
    public static int wtf(String tag, String msg) { return println('A', tag, msg); }
    public static boolean isLoggable(String tag, int level) { return true; }
    public static int println(int priority, String tag, String msg) {
        char c = "VDWIEA".charAt(Math.max(0, Math.min(priority - 2, 5)));
        return println(c, tag, msg);
    }
    public static String getStackTraceString(Throwable t) {
        if (t == null) return "";
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
    private static int println(char level, String tag, String msg) {
        System.out.println("[" + level + "/" + tag + "] " + msg);
        return 0;
    }
}
