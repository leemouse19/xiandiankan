package com.tvshell.moontv;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.widget.Toast;

import java.io.File;

/**
 * FTP 命令执行器（嵌入版）。
 *
 * 支持的命令（通过 JSON 中的 "action" 字段区分）：
 *   ping          心跳检测
 *   tap           模拟点击 (x, y) —— 仅限应用内（通过 AccessibilityService 或当前 Activity）
 *   key           模拟按键 (code) —— 仅限应用内
 *   input         文本注入 (text) —— 仅限应用内
 *   screenshot    截图（写 PNG 到 FTP 目录）
 *   dump_ui       导出 UI 树 JSON（用应用自身 View 树，不依赖无障碍服务）
 *   perf          性能快照
 *   diag          诊断日志信息
 *   list_files    列出 FTP 目录文件
 *   start_activity 启动 Activity (component)
 *   uninstall     卸载应用 (package)
 *   wake          唤醒/休眠
 *   clear_cmd     清空命令目录
 *   quit          退出调试面板
 *   toast         弹 Toast
 *
 * 注意：tap/key/input 在没有 AccessibilityService 时仅限应用内操作。
 */
public class CmdExecutor {

    private static final String TAG = "moontv-cmd";
    private final Context mCtx;
    private final UiDumper mUiDumper;

    public CmdExecutor(Context ctx, UiDumper uiDumper) {
        mCtx = ctx;
        mUiDumper = uiDumper;
    }

    /**
     * 执行命令并返回结果字符串。
     */
    public String execute(String action, String cmdJson) {
        if (action == null) return "null action";

        switch (action) {
            case "ping":
                return "pong";

            case "tap":
                return doTap(cmdJson);

            case "key":
                return doKey(cmdJson);

            case "input":
                return doInput(cmdJson);

            case "screenshot":
                return doScreenshot();

            case "dump_ui":
                return doDumpUi();

            case "perf":
                return doPerf();

            case "diag":
                return doDiag();

            case "list_files":
                return doListFiles(cmdJson);

            case "start_activity":
                return doStartActivity(cmdJson);

            case "uninstall":
                return doUninstall(cmdJson);

            case "wake":
                return doWake(cmdJson);

            case "clear_cmd":
                return doClearCmd();

            case "quit":
                return doQuit();

            case "toast":
                return doToast(cmdJson);

            default:
                Diag.e("CMD", "unknown action: " + action, null);
                return "unknown action: " + action;
        }
    }

    private String doTap(String json) {
        int x = extractInt(json, "x", 0);
        int y = extractInt(json, "y", 0);
        Diag.i("CMD", "tap x=" + x + " y=" + y);
        // 应用内点击：通过 UiDumper 的当前 Activity 执行
        if (mUiDumper != null) {
            boolean ok = mUiDumper.tap(x, y);
            return ok ? "tapped (" + x + "," + y + ")" : "tap failed";
        }
        return "ui dumper not available";
    }

    private String doKey(String json) {
        int code = extractInt(json, "code", 0);
        Diag.i("CMD", "key code=" + code);
        if (mUiDumper != null) {
            boolean ok = mUiDumper.key(code);
            return ok ? "key " + code + " sent" : "key failed";
        }
        return "ui dumper not available";
    }

    private String doInput(String json) {
        String text = extractString(json, "text");
        Diag.i("CMD", "input text=" + text);
        if (mUiDumper != null) {
            boolean ok = mUiDumper.inputText(text);
            return ok ? "input sent" : "input failed";
        }
        return "ui dumper not available";
    }

    private String doScreenshot() {
        Diag.i("CMD", "screenshot");
        if (mUiDumper != null) {
            String path = mUiDumper.takeScreenshot();
            return path != null ? "screenshot: " + path : "screenshot failed";
        }
        return "ui dumper not available";
    }

    private String doDumpUi() {
        Diag.i("CMD", "dump_ui");
        if (mUiDumper != null) {
            String json = mUiDumper.dumpCurrent();
            if (json != null) {
                Diag.writeUiJson(json);
                return "ui dumped (" + json.length() + " bytes)";
            }
        }
        return "ui dump failed";
    }

    private String doPerf() {
        Diag.i("CMD", "perf");
        // 简单性能快照
        long heapUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long heapMax = Runtime.getRuntime().maxMemory();
        int[] pids = new int[] { android.os.Process.myPid() };
        android.app.ActivityManager am = (android.app.ActivityManager) mCtx.getSystemService(Context.ACTIVITY_SERVICE);
        int pss = 0;
        if (am != null) {
            try {
                Class<?> pmInfoCls = Class.forName("android.app.ActivityManager$ProcessMemoryInfo");
                java.lang.reflect.Method m = android.app.ActivityManager.class.getMethod("getProcessMemoryInfo", int[].class);
                Object[] infos = (Object[]) m.invoke(am, pids);
                if (infos != null && infos.length > 0) {
                    java.lang.reflect.Method getTotalPss = pmInfoCls.getMethod("getTotalPss");
                    pss = (int) getTotalPss.invoke(infos[0]);
                }
            } catch (Throwable t) {
                pss = (int) (heapUsed / 1024);
            }
        }
        return "pss=" + pss + "KB heapUsed=" + (heapUsed / 1024) + "KB heapMax=" + (heapMax / 1024) + "KB";
    }

    private String doDiag() {
        Diag.i("CMD", "diag");
        File f = new File(Diag.getFtpSharedDir(mCtx), "moontv_diag.txt");
        if (f.exists()) {
            return "diag file: " + f.getAbsolutePath() + " (" + f.length() + " bytes)";
        }
        return "diag file not found";
    }

    private String doListFiles(String json) {
        String subdir = extractString(json, "dir");
        File base = Diag.getFtpSharedDir(mCtx);
        if (base == null) return "no FTP dir";
        File target = subdir != null ? new File(base, subdir) : base;
        if (!target.exists() || !target.isDirectory()) return "dir not found: " + target;
        File[] files = target.listFiles();
        if (files == null) return "empty dir";
        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            sb.append(f.getName()).append(f.isDirectory() ? "/" : " (" + f.length() + ") ");
        }
        return sb.toString();
    }

    private String doStartActivity(String json) {
        String component = extractString(json, "component");
        if (component == null) return "missing component";
        Diag.i("CMD", "start_activity " + component);
        try {
            String[] parts = component.split("/");
            if (parts.length != 2) return "invalid component format (use pkg/Activity)";
            Intent intent = new Intent();
            intent.setComponent(new android.content.ComponentName(parts[0], parts[1]));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mCtx.startActivity(intent);
            return "started " + component;
        } catch (Throwable t) {
            return "start failed: " + t.getMessage();
        }
    }

    private String doUninstall(String json) {
        String pkg = extractString(json, "package");
        if (pkg == null) return "missing package";
        Diag.i("CMD", "uninstall " + pkg);
        try {
            Intent intent = new Intent(Intent.ACTION_DELETE);
            intent.setData(android.net.Uri.parse("package:" + pkg));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mCtx.startActivity(intent);
            return "uninstall initiated for " + pkg;
        } catch (Throwable t) {
            return "uninstall failed: " + t.getMessage();
        }
    }

    private String doWake(String json) {
        boolean wake = !"0".equals(extractString(json, "mode"));
        Diag.i("CMD", "wake=" + wake);
        try {
            PowerManager pm = (PowerManager) mCtx.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "moontv:cmd");
            if (wake) {
                wl.acquire(5000);
                return "woken";
            } else {
                wl.release();
                return "released";
            }
        } catch (Throwable t) {
            return "wake fail: " + t.getMessage();
        }
    }

    private String doClearCmd() {
        Diag.i("CMD", "clear_cmd");
        File cmdDir = Diag.getCmdDir();
        if (cmdDir != null && cmdDir.exists()) {
            File[] files = cmdDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().startsWith("cmd_")) f.delete();
                }
            }
        }
        return "cmd dir cleared";
    }

    private String doQuit() {
        Diag.i("CMD", "quit");
        return "quit received";
    }

    private String doToast(String json) {
        String msg = extractString(json, "message");
        Diag.i("CMD", "toast: " + msg);
        final String m = msg;
        new android.os.Handler(mCtx.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                android.widget.Toast.makeText(mCtx, m, Toast.LENGTH_SHORT).show();
            }
        });
        return "toast: " + msg;
    }

    // ── helpers ──

    private static String extractString(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int start = json.indexOf("\"", idx + key.length() + 1);
        if (start < 0) return null;
        int end = json.indexOf("\"", start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    private static int extractInt(String json, String key, int def) {
        String s = extractString(json, key);
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }
}