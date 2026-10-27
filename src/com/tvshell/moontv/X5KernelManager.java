package com.tvshell.moontv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * X5 内核管理器（进程级单例）。
 *
 * 职责：
 * 1. 冷启动时初始化 X5 内核（加载内置离线包 / 检查在线更新）
 * 2. 检测内核版本与可用性
 * 3. 内核不可用时通知降级到系统 WebView
 * 4. 提供 X5 WebView 工厂方法
 *
 * X5 SDK 关键类（tbssdk.jar）：
 *   com.tencent.smtt.sdk.QbSdk        - 内核初始化与状态检测
 *   com.tencent.smtt.sdk.WebView      - X5 WebView（替代 android.webkit.WebView）
 *   com.tencent.smtt.sdk.WebSettings  - X5 WebView 配置
 *   com.tencent.smtt.sdk.QbSdk$PreInitCallback - 初始化回调接口
 */
public final class X5KernelManager {

    private static final String TAG = "moontv-x5";
    private static X5KernelManager sInstance;

    public static final int STATUS_UNKNOWN = 0;
    public static final int STATUS_READY = 1;
    public static final int STATUS_FAILED = 2;

    private int mStatus = STATUS_UNKNOWN;
    private String mKernelVersion = "";
    private boolean mInitialized = false;
    private final List<Callback> mPendingCbs = new ArrayList<Callback>();
    private Context mAppCtx;

    private X5KernelManager() {
    }

    public static synchronized X5KernelManager get() {
        if (sInstance == null) {
            sInstance = new X5KernelManager();
        }
        return sInstance;
    }

    /**
     * 初始化 X5 内核（后台线程，非阻塞）。回调在主线程执行。
     */
    public void init(final Context ctx, final Callback cb) {
        if (mInitialized) {
            if (mStatus == STATUS_UNKNOWN && cb != null) {
                synchronized (mPendingCbs) { mPendingCbs.add(cb); }
            } else if (cb != null) {
                cb.onResult(mStatus, mKernelVersion);
            }
            return;
        }
        mInitialized = true;
        if (ctx != null) mAppCtx = ctx.getApplicationContext();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    loadOfflineKernel(ctx);
                    boolean ok = initX5SDK(ctx);
                    if (ok) {
                        mStatus = STATUS_READY;
                        mKernelVersion = getKernelVersionInternal();
                        Log.i(TAG, "X5 kernel ready: " + mKernelVersion);
                    } else {
                        mStatus = STATUS_FAILED;
                        mKernelVersion = "unavailable";
                        Log.w(TAG, "X5 kernel init failed, fallback to system WebView");
                    }
                } catch (Throwable t) {
                    mStatus = STATUS_FAILED;
                    mKernelVersion = "error: " + t.getMessage();
                    Log.e(TAG, "X5 kernel init exception: " + t, t);
                }

                final int status = mStatus;
                final String ver = mKernelVersion;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) cb.onResult(status, ver);
                        List<Callback> snapshot;
                        synchronized (mPendingCbs) {
                            snapshot = new ArrayList<Callback>(mPendingCbs);
                            mPendingCbs.clear();
                        }
                        for (Callback p : snapshot) {
                            if (p != null) p.onResult(status, ver);
                        }
                    }
                });
            }
        }, "x5-init").start();
    }

    public int getStatus() { return mStatus; }
    public String getKernelVersion() { return mKernelVersion; }
    public boolean isReady() { return mStatus == STATUS_READY; }
    public boolean shouldFallback() { return mStatus == STATUS_FAILED; }

    /**
     * 加载内置离线包。
     */
    private void loadOfflineKernel(Context ctx) {
        cleanStaleCoreSo(ctx);
        String destPath = copyOfflinePackage(ctx);
        if (destPath == null) {
            Log.w(TAG, "loadOfflineKernel: no offline package, relying on cloud download");
            return;
        }
        boolean is64 = android.os.Process.is64Bit();
        int versionCode = readCoreVersion(ctx, destPath);
        if (versionCode <= 0) versionCode = is64 ? 46285 : 46284;
        try {
            Class<?> qbsdkClass = Class.forName("com.tencent.smtt.sdk.QbSdk");
            java.lang.reflect.Method m = qbsdkClass.getMethod(
                "installLocalTbsCore", Context.class, int.class, String.class);
            Boolean ok = (Boolean) m.invoke(null, ctx, versionCode, destPath);
            Log.i(TAG, "installLocalTbsCore(" + (is64 ? "arm64" : "armeabi") + ", v" + versionCode
                    + ") = " + ok);
        } catch (Throwable t) {
            Log.w(TAG, "installLocalTbsCore failed: " + t.getMessage());
        }
    }

    private void cleanStaleCoreSo(Context ctx) {
        try {
            File tbsDir = ctx.getDir("app_tbs_64", Context.MODE_PRIVATE);
            if (tbsDir == null || !tbsDir.exists()) return;
            boolean is64 = android.os.Process.is64Bit();
            int deleted = 0;
            java.util.ArrayDeque<File> stack = new java.util.ArrayDeque<File>();
            stack.push(tbsDir);
            while (!stack.isEmpty()) {
                File d = stack.pop();
                File[] fs = d.listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    if (f.isDirectory()) { stack.push(f); continue; }
                    if (!f.getName().endsWith(".so")) continue;
                    if (isElfWrongBitness(f, is64)) {
                        if (f.delete()) deleted++;
                    }
                }
            }
            if (deleted > 0) {
                Log.i(TAG, "cleanStaleCoreSo: removed " + deleted + " stale .so");
            }
        } catch (Throwable t) {
            Log.w(TAG, "cleanStaleCoreSo failed: " + t.getMessage());
        }
    }

    private boolean isElfWrongBitness(File f, boolean want64) {
        java.io.FileInputStream fis = null;
        try {
            fis = new java.io.FileInputStream(f);
            byte[] h = new byte[20];
            if (fis.read(h) < 20) return false;
            if (h[0] != 0x7f || h[1] != 'E' || h[2] != 'L' || h[3] != 'F') return false;
            int machine = (h[18] & 0xff) | (h[19] << 8);
            boolean is64 = (machine == 183);
            boolean is32 = (machine == 40);
            if (is64 || is32) return is64 != want64;
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (fis != null) fis.close(); } catch (Throwable ignore) {}
        }
    }

    private String copyOfflinePackage(Context ctx) {
        boolean is64 = android.os.Process.is64Bit();
        String abi = is64 ? "arm64_v8a" : "armeabi_v7a";
        String destName = is64 ? "tbs_core_release_arm64.tbs.apk" : "tbs_core_release.tbs.apk";
        File dir = new File(ctx.getFilesDir(), "x5kernel");
        if (!dir.exists()) dir.mkdirs();
        File dest = new File(dir, destName);
        if (dest.exists() && dest.length() > 1000000) {
            Log.i(TAG, "offline package already exists: " + dest.length() + " bytes");
            return dest.getAbsolutePath();
        }
        String assetRel = abi + "/tbs_core_release.tbs.apk";
        try {
            InputStream is = ctx.getAssets().open(assetRel);
            FileOutputStream fos = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
            fos.close(); is.close();
            Log.i(TAG, "copied assets/" + assetRel + " -> " + dest.getAbsolutePath()
                    + " (" + dest.length() + " bytes)");
            return dest.getAbsolutePath();
        } catch (Throwable t) {
            Log.e(TAG, "copyOfflinePackage failed for " + assetRel + ": " + t, t);
        }
        return null;
    }

    private int readCoreVersion(Context ctx, String path) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageArchiveInfo(path, 0);
            if (pi != null) {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    return (int) pi.getLongVersionCode();
                }
                return pi.versionCode;
            }
        } catch (Throwable t) {
            Log.w(TAG, "readCoreVersion failed: " + t.getMessage());
        }
        return -1;
    }

    private boolean initX5SDK(Context ctx) {
        try {
            Class<?> qbsdkClass = Class.forName("com.tencent.smtt.sdk.QbSdk");
            final boolean[] result = {false};
            final Object lock = new Object();

            Class<?> preInitCallbackClass = Class.forName("com.tencent.smtt.sdk.QbSdk$PreInitCallback");
            Object callback = java.lang.reflect.Proxy.newProxyInstance(
                preInitCallbackClass.getClassLoader(),
                new Class<?>[]{preInitCallbackClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                        String name = method.getName();
                        if ("onViewInitFinished".equals(name) && args != null && args.length == 1) {
                            boolean success = Boolean.TRUE.equals(args[0]);
                            Log.i(TAG, "onViewInitFinished(success=" + success + ")");
                            synchronized (lock) {
                                result[0] = success;
                                lock.notifyAll();
                            }
                        } else if ("onCoreInitFinished".equals(name)) {
                            Log.i(TAG, "onCoreInitFinished()");
                        }
                        return null;
                    }
                }
            );

            qbsdkClass.getMethod("initX5Environment", Context.class, preInitCallbackClass)
                      .invoke(null, ctx, callback);

            synchronized (lock) {
                if (!result[0]) {
                    lock.wait(15000);
                }
            }

            Boolean x5Ready = (Boolean) qbsdkClass.getMethod("isX5Core").invoke(null);
            if (x5Ready != null && x5Ready) {
                Log.i(TAG, "X5 core ready via isX5Core()");
                return true;
            }

            Boolean canLoad = (Boolean) qbsdkClass.getMethod("canLoadX5", Context.class).invoke(null, ctx);
            if (canLoad != null && canLoad) {
                Log.i(TAG, "X5 canLoadX5=true, give it another 2s");
                Thread.sleep(2000);
                x5Ready = (Boolean) qbsdkClass.getMethod("isX5Core").invoke(null);
                return x5Ready != null && x5Ready;
            }

            Log.w(TAG, "X5 core not available: isX5Core=" + x5Ready + ", canLoadX5=" + canLoad);
            return false;

        } catch (ClassNotFoundException e) {
            Log.w(TAG, "X5 SDK not found: " + e.getMessage());
            return false;
        } catch (Throwable t) {
            Log.e(TAG, "X5 SDK init error: " + t, t);
            return false;
        }
    }

    private String getKernelVersionInternal() {
        try {
            Class<?> qbsdkClass = Class.forName("com.tencent.smtt.sdk.QbSdk");
            if (mAppCtx != null) {
                Integer version = (Integer) qbsdkClass.getMethod("getTbsVersion", Context.class).invoke(null, mAppCtx);
                if (version != null && version > 0) {
                    return "TBS " + version;
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return "TBS (loaded)";
    }

    /**
     * 创建 X5 WebView。返回 Object（com.tencent.smtt.sdk.WebView），调用方需转型。
     * X5 不可用时返回 null。
     */
    public Object createWebView(Context ctx) {
        if (!isReady()) {
            Log.w(TAG, "createWebView called but X5 not ready, status=" + mStatus);
            return null;
        }
        try {
            Class<?> x5WebViewClass = Class.forName("com.tencent.smtt.sdk.WebView");
            return x5WebViewClass.getConstructor(Context.class).newInstance(ctx);
        } catch (Throwable t) {
            Log.e(TAG, "createWebView failed: " + t, t);
            return null;
        }
    }

    public interface Callback {
        void onResult(int status, String kernelVersion);
    }
}