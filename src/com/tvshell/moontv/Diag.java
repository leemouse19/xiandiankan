package com.tvshell.moontv;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 自记录诊断模块（无 adb 环境下改用 FTP 共享目录取日志）。
 *
 * - init() 必须尽早调用（MoontvApp.onCreate 第一行）：记录设备信息 + 安装全局崩溃处理器 + 首次 logcat 快照
 * - 各 Activity 在关键节点调用 i()/e()；在 onPause 调用 dumpLogcat() 刷新一次系统日志
 * - 日志写到 FTP 共享根目录下可读路径：优先 /sdcard/Download/moontv_diag.txt，失败回退到应用私有外部目录
 * - 取日志方（本机）直接 FTP GET 这两个路径之一即可
 *
 * 内存安全：dumpLogcat 在后台线程执行；单文件封顶 2MB（超出保留尾部）；不常驻大对象。
 */
public class Diag {

    private static final String TAG = "MOONTV_DIAG";
    private static final long MAX_FILE = 2L * 1024 * 1024;
    private static final int LOGCAT_LINES = 5000;

    private static File sFile;
    private static File sDir;       // FTP 共享目录
    private static File sPerf;      // moontv_perf.csv
    private static File sUi;        // moontv_ui.json
    private static boolean sInit;
    private static Context sCtx;
    private static final long MAX_PERF = 100000;  // CSV 滚动阈值

    public static void init(Context ctx) {
        if (sInit) return;
        sInit = true;
        sCtx = ctx.getApplicationContext();
        // 选择可写路径（优先 Download，便于 FTP 读取；失败回退应用私有目录）
        File f = null;
        try {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dl != null) {
                dl.mkdirs();
                f = new File(dl, "moontv_diag.txt");
                sDir = dl;
                sPerf = new File(sDir, "moontv_perf.csv");
                sUi = new File(sDir, "moontv_ui.json");
                // CSV 表头
                if (!sPerf.exists()) writeLine(sPerf, "timestamp,cpu,mem_pss_kb,mem_rss_kb,fps,rx_bytes,tx_bytes");
                append(f, "=== MOONTV diag init (Download) " + now() + " ===\n");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Download path unavailable, fallback", t);
        }
        if (f == null || !canWrite(f)) {
            try {
                File ef = ctx.getExternalFilesDir(null);
                if (ef != null) {
                    f = new File(ef, "moontv_diag.txt");
                    sDir = ef;
                    sPerf = new File(sDir, "moontv_perf.csv");
                    sUi = new File(sDir, "moontv_ui.json");
                    if (!sPerf.exists()) writeLine(sPerf, "timestamp,cpu,mem_pss_kb,mem_rss_kb,fps,rx_bytes,tx_bytes");
                    append(f, "=== MOONTV diag init (filesdir) " + now() + " ===\n");
                }
            } catch (Throwable t2) {
                Log.e(TAG, "filesdir unavailable", t2);
            }
        }
        sFile = f;
        i("DEVICE", "model=" + Build.MODEL + " brand=" + Build.BRAND
                + " sdk=" + Build.VERSION.SDK_INT + " release=" + Build.VERSION.RELEASE
                + " abi=" + java.util.Arrays.toString(Build.SUPPORTED_ABIS));
        i("APP", "pkg=com.tvshell.moontv ver=" + BuildConfigVer());
        // 全局崩溃捕获
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                e("CRASH", "uncaught in " + t.getName(), e);
                dumpLogcat();
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
        // 首次 logcat 快照
        dumpLogcat();
    }

    private static String BuildConfigVer() {
        try {
            android.content.pm.PackageInfo pi = sCtx.getPackageManager()
                    .getPackageInfo(sCtx.getPackageName(), 0);
            return pi.versionName + "/" + pi.versionCode;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    public static void i(String tag, String msg) {
        append(sFile, "I/" + tag + ": " + msg + "\n");
    }

    public static void e(String tag, String msg, Throwable ex) {
        StringBuilder sb = new StringBuilder("E/" + tag + ": " + msg);
        if (ex != null) sb.append("\n").append(Log.getStackTraceString(ex));
        append(sFile, sb.toString() + "\n");
    }

    /** 后台线程 dump 系统 logcat（仅本进程日志在 Android 11+ 受限，但 X5/TBS 与本 App 同进程，足够）。 */
    public static void dumpLogcat() {
        if (sFile == null || sCtx == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Process p = Runtime.getRuntime().exec("logcat -d -v time");
                    InputStream is = p.getInputStream();
                    BufferedReader r = new BufferedReader(new InputStreamReader(is));
                    FileWriter w = new FileWriter(sFile, true);
                    w.write("--- logcat dump " + now() + " ---\n");
                    String l;
                    int n = 0;
                    while ((l = r.readLine()) != null) {
                        w.write(l);
                        w.write("\n");
                        if (++n >= LOGCAT_LINES) break;
                    }
                    w.write("--- logcat end (lines=" + n + ") ---\n");
                    w.flush();
                    w.close();
                    is.close();
                    p.destroy();
                    trimIfNeeded(sFile);
                } catch (Throwable t) {
                    Log.e(TAG, "dump logcat fail", t);
                }
            }
        }, "moontv-diag").start();
    }

    private static boolean canWrite(File f) {
        try {
            append(f, "");
            return f.exists() && f.canWrite();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void append(File f, String s) {
        if (f == null) return;
        try (FileWriter w = new FileWriter(f, true)) {
            if (s != null && !s.isEmpty()) w.write(s);
        } catch (IOException ignore) {
        }
        trimIfNeeded(f);
    }

    private static void trimIfNeeded(File f) {
        try {
            if (f.length() <= MAX_FILE) return;
            File tmp = new File(f.getParent(), f.getName() + ".tmp");
            try (FileInputStream in = new FileInputStream(f);
                 BufferedReader r = new BufferedReader(new InputStreamReader(in));
                 FileWriter out = new FileWriter(tmp)) {
                // 跳过前半部分，保留尾部
                long skip = f.length() - MAX_FILE / 2;
                in.skip(skip);
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) != -1) out.write(buf, 0, n);
            }
            if (tmp.exists() && tmp.length() > 0) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (Throwable t) {
            Log.e(TAG, "trim fail", t);
        }
    }

    private static String now() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    // ── 嵌入式调试支持（与 DebugCompanion 协议对齐）──

    public static File getFtpSharedDir(Context ctx) {
        try {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dl != null) { dl.mkdirs(); if (dl.canWrite()) return dl; }
        } catch (Throwable ignore) {}
        try {
            File sd = new File("/sdcard/Download");
            if (!sd.exists()) sd.mkdirs();
            if (sd.exists() && sd.canWrite()) return sd;
        } catch (Throwable ignore) {}
        try {
            File ef = ctx.getExternalFilesDir(null);
            if (ef != null) { ef.mkdirs(); return ef; }
        } catch (Throwable ignore) {}
        return null;
    }

    public static File getCmdDir() {
        return sDir != null ? new File(sDir, "moontv_cmd") : null;
    }

    public static void writeUiJson(String json) {
        if (sUi == null) return;
        try (FileWriter w = new FileWriter(sUi)) {
            w.write(json);
        } catch (IOException ignore) {}
    }

    public static void writePerfCsv(float cpu, int pssKb, int rssKb, float fps, long rx, long tx) {
        if (sPerf == null) return;
        String line = now() + "," + cpu + "," + pssKb + "," + rssKb + "," + fps + "," + rx + "," + tx;
        try (FileWriter w = new FileWriter(sPerf, true)) {
            w.write(line); w.write("\n");
        } catch (IOException ignore) {}
        if (sPerf.length() > MAX_PERF) rollFile(sPerf, 1000);
    }

    public static void writeLine(File f, String line) {
        if (f == null) return;
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(line); w.write("\n");
        } catch (IOException ignore) {}
    }

    private static void rollFile(File f, int keep) {
        try {
            File tmp = new File(f.getParent(), f.getName() + ".tmp");
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
            int total = 0;
            while (r.readLine() != null) total++;
            r.close();
            int skip = Math.max(0, total - keep);
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
            for (int i = 0; i < skip; i++) r.readLine();
            FileWriter out = new FileWriter(tmp);
            String l;
            while ((l = r.readLine()) != null) { out.write(l); out.write("\n"); }
            r.close(); out.flush(); out.close();
            if (tmp.exists() && tmp.length() > 0) { f.delete(); tmp.renameTo(f); }
        } catch (Throwable ignore) {}
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
