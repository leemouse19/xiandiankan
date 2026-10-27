package com.tvshell.moontv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 登录凭据（auth cookie）管理。
 *
 * 【LunaTV 契约要点】老板部署的是 MoontechLab 的 LunaTV，它的 auth cookie 不是随机 token，
 * 而是 encodeURIComponent(JSON.stringify({role, username, signature, timestamp}))：
 *   - redis 模式：含 username + signature（HMAC-SHA256(username, PASSWORD)）+ timestamp
 *   - localstorage 模式：含 password + role
 * 服务端 middleware 会 decodeURIComponent 后 JSON.parse，再校验 signature。
 *
 * 因此保存时必须<b>原样保存 cookie 值</b>，不能解码、不能重新编码。
 * 之前按旧版（随机 token）逻辑把整条 Set-Cookie 串（含 Path/Expires 等属性）原样存下来，
 * 再用 substring(5) + ';' 截断去注入 —— LunaTV 的值本身是 URL 编码的 JSON，
 * 含有 %3D/%22 等字符，虽不至于被 ';' 截坏，但把属性一起存下来在边界情况下会带上
 * "Path=/" 之类的脏尾巴。统一改成：保存时取干净的值，注入时原样使用。
 */
public class AuthHelper {

    private static final String TAG = "moontv-auth";
    private static final String PREFS = "moontv_auth";
    private static final String KEY_COOKIE = "auth_cookie";
    /** 上次登录成功的用户名（redis 模式必填，记住省得每次输）。 */
    private static final String KEY_LAST_USER = "last_user";

    /** 取上次登录成功的用户名。 */
    public static String getLastUser(Context ctx) {
        if (ctx == null) return "";
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_USER, "");
    }

    /** 记住登录成功的用户名。 */
    public static void saveLastUser(Context ctx, String user) {
        if (ctx == null || user == null || user.isEmpty()) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_USER, user).apply();
    }

    /** 是否已登录（本地存在保存的 auth cookie 即视为已登录）。 */
    public static boolean isLoggedIn(Context ctx) {
        return getSavedCookie(ctx) != null;
    }

    /** 取已保存的 auth cookie 值（不含 "auth=" 前缀）。 */
    public static String getSavedCookie(Context ctx) {
        if (ctx == null) return null;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String c = sp.getString(KEY_COOKIE, null);
        return (c == null || c.isEmpty()) ? null : c;
    }

    /**
     * 保存 auth cookie。
     *
     * @param rawCookie 完整的 Set-Cookie 值（形如 auth=xxx; Path=/; ...），也接受纯 cookie 值
     */
    public static void saveCookie(Context ctx, String rawCookie) {
        if (ctx == null || rawCookie == null) return;
        String value = extractValue(rawCookie);
        if (value == null || value.isEmpty()) {
            Log.w(TAG, "save auth cookie: empty value");
            return;
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_COOKIE, value).apply();
        Log.i(TAG, "save auth cookie ok len=" + value.length());
    }

    /**
     * 从 Set-Cookie 串里取出 auth 的干净值。
     *
     * Set-Cookie: auth=&lt;value&gt;; Path=/; Expires=...; SameSite=lax
     * value 是 URL 编码的 JSON，不含裸的 ';'（会被编码成 %3B），所以按第一个 ';' 截断安全。
     */
    private static String extractValue(String raw) {
        String s = raw.trim();
        if (s.startsWith("auth=")) s = s.substring(5);
        int semi = s.indexOf(';');
        if (semi >= 0) s = s.substring(0, semi);
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** 清除登录态。 */
    public static void clear(Context ctx) {
        if (ctx != null) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_COOKIE).apply();
        }
        try {
            MoontvApi.clearCookies();
        } catch (Throwable t) {
            Log.w(TAG, "clear cookie failed", t);
        }
    }

    /** 把已保存的 auth cookie 注入 java.net 的 CookieManager（站点 host），供后续接口自动带登录态。 */
    public static void seedJavaNet(Context ctx) {
        try {
            MoontvApi.seedAuthCookie(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "seed java.net cookie failed", t);
        }
    }
}
