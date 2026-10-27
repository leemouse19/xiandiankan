package com.tvshell.moontv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 闪屏 Activity（启动入口）。
 *
 * 职责：
 * - 最小停留（600ms）后按登录态分流：已登录 → 原生首页；未登录 → 原生登录界面
 * - X5 内核在 MoontvApp 里后台预热，此处只订阅回调记日志，**不阻塞跳转**
 *   （全原生首页不依赖内核，等内核反而会白等最多 20 秒）
 *
 * 内存：不持有 WebView；跳转后即 finish，自身可被回收。
 */
public class SplashActivity extends Activity {

    private static final String TAG = "moontv-splash";
    private static final long MIN_STAY_MS = 600;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mLaunched = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        ThemeManager.applyDarkChrome(this);

        // 【内存】这里**不**初始化 X5 内核。
        // 低内存设备（API 25 / 堆 192MB）上，启动就载入 X5 内核 dex 会直接吃穿堆，
        // 首页加载海报时必崩 OOM。改为进播放器时按需初始化。
        // 详见 MoontvApp 的说明。

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                launchTarget();
            }
        }, MIN_STAY_MS);
    }

    /** 根据登录态决定落地页：已登录 → 首页；未登录 → 原生登录界面。 */
    private void launchTarget() {
        if (mLaunched) return;
        mLaunched = true;
        mHandler.removeCallbacksAndMessages(null);
        // 保证已保存的 auth cookie 注入 java.net CookieManager，所有接口请求自动带登录态
        AuthHelper.seedJavaNet(this);
        Intent i;
        if (AuthHelper.isLoggedIn(this)) {
            Diag.i("SPLASH", "already logged in -> MainActivity");
            i = new Intent(this, MainActivity.class);
        } else {
            Diag.i("SPLASH", "not logged in -> LoginActivity");
            i = new Intent(this, LoginActivity.class);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Diag.dumpLogcat();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
    }
}
