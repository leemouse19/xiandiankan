package com.tvshell.moontv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.View;

/**
 * 主题管理（轻量）。
 *
 * 网页版 MOONTV 自身负责页面内的明暗主题；原生侧只管理「状态栏 / 导航栏」配色，
 * 保证 WebView 全屏铺满时边缘不露白。Phase 6（TV 适配）可在此扩展遥控焦点高亮等。
 */
public final class ThemeManager {

    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String THEME_SYSTEM = "system";

    private static final String PREF = "moontv_pref";
    private static final String KEY = "theme";

    private ThemeManager() {}

    public static String getTheme(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return sp.getString(KEY, THEME_DARK);
    }

    public static void setTheme(Context ctx, String theme) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().putString(KEY, theme).apply();
    }

    /** 把状态栏 / 导航栏刷成深色（与深色页面一致），避免 WebView 边缘露白。 */
    public static void applyDarkChrome(Activity act) {
        if (Build.VERSION.SDK_INT >= 21) {
            act.getWindow().setStatusBarColor(0xFF0F1115);
            act.getWindow().setNavigationBarColor(0xFF0F1115);
        }
        // 全屏沉浸式：刘海/挖孔屏下内容延伸到状态栏后，状态栏图标用浅色
        if (Build.VERSION.SDK_INT >= 23) {
            int flags = act.getWindow().getDecorView().getSystemUiVisibility();
            // 清除 LIGHT_STATUS_BAR 位 -> 状态栏图标为浅色（深色背景上可见）
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            act.getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }
}
