package com.tvshell.moontv;

import android.app.Application;
import android.util.Log;

/**
 * 享看电影 Application 类。
 *
 * 职责：
 * - 全局配置（主题、默认 UA）
 * - 内存吃紧时清图片缓存
 *
 * 【内存事故 · 2026-10-26】LANDI AECR C5 PRO（Android 7.1.2 / API 25）上
 * 首页必崩：java.lang.OutOfMemoryError，堆栈落在 BitmapFactory.decodeFile。
 * 该设备 Java 堆只有 192MB，而 **API 25 的 Bitmap 像素数据是算在 Java 堆里的**
 * （ART 非移动空间），几十张海报就能吃满；X5 内核的 dex 又在同进程加载，
 * 两边抢同一块堆。处置：
 *   ① ImageLoader 全面采样 + RGB_565（海报要 4B→2B/像素）+ 后台解码
 *   ② **X5 内核不再在 Application 启动时预热**（它一加载就吃掉几十 MB 堆，
 *      而首页/搜索/详情全是原生 UI，根本用不到 WebView）——
 *      改为进入播放器时按需初始化（PlayerActivity 已有"等内核就绪"逻辑），
 *      离线内核包本地已有，实测初始化约 1 秒。
 *   ③ 系统内存吃紧时清图片缓存（onTrimMemory / onLowMemory）。
 */
public class MoontvApp extends Application {

    private static final String TAG = "moontv";
    private static MoontvApp sInstance;

    // 默认 User-Agent（追加 Android 标识，保留网页版 UA 特征）
    public static final String DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36 " +
        "MOONTV-Android/1.0";

    // 网页版地址
    public static final String SITE_URL = "https://moontv.leemousebgs.dpdns.org";

    @Override
    public void onCreate() {
        super.onCreate();
        // 诊断：尽早初始化，捕获后续所有崩溃与关键信息（无 adb 时改由 FTP 取日志）
        Diag.init(this);
        sInstance = this;
        Log.i(TAG, "MoontvApp onCreate");

        // 启动即把已保存的登录 cookie 注入 java.net 的 CookieManager，后续所有接口请求自动带登录态
        AuthHelper.seedJavaNet(this);

        // 【内存】这里**不再**预热 X5 内核。
        // 理由：X5 一初始化就把几十 MB 的内核 dex 载入本进程 Java 堆，
        // 而首页/搜索/详情/直播全是原生 UI，根本用不到 WebView；
        // 放在启动时预热会直接把低内存设备（API 25，堆仅 192MB）的堆吃穿，
        // 导致首页一加载海报就 OOM 崩溃。
        // 现在改为进入播放器时按需初始化（PlayerActivity 已有等内核就绪的逻辑）。

        // ── 嵌入式 FTP 调试桥接（调试版专用，最终包注释掉即可） ──
        startDebugBridge();
    }

    /** 启动 FTP 命令轮询，使本应用能被 DebugCompanion 远程调试。最终包注释掉。 */
    private void startDebugBridge() {
        try {
            UiDumper uiDumper = new UiDumper(this);
            CmdExecutor executor = new CmdExecutor(this, uiDumper);
            FtpCmdBridge bridge = new FtpCmdBridge(this, executor);
            bridge.start();
            Diag.i("BRIDGE", "debug bridge started");
        } catch (Throwable t) {
            Diag.e("BRIDGE", "start fail", t);
        }
    }

    /** 系统内存吃紧 → 清掉图片内存缓存，把堆还给系统。 */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            ImageLoader.clearMemory();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        ImageLoader.clearMemory();
    }

    public static MoontvApp getInstance() {
        return sInstance;
    }
}