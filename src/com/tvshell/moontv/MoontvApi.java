package com.tvshell.moontv;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MOONTV / LunaTV 后端接口客户端（原生直连，取代 WebView 套壳）。
 *
 * 【重要】老板的部署是 MoontechLab 的 <b>LunaTV</b>（ghcr.io/moontechlab/lunatv，
 * server-config 返回 Version 100.0.1 / StorageType redis），不是上游 Stardm0/MoonTV。
 * 两者接口契约有实质差异，本类按 LunaTV 契约实现：
 *   POST /api/login            登录。失败返回 {ok:false, error:"..."}（注意是 error 不是 message）
 *                              localstorage 模式只需 password；redis 模式需 username+password
 *   GET  /api/search?q=        多源搜索。results[].episodes 是 string[]（可直接播的 m3u8）
 *                              LunaTV 已过滤掉 episodes 为空的结果，故搜索结果必定可播
 *   GET  /api/detail?id=&source= 详情，同款 SearchResult 结构
 *   GET  /api/douban?type=&tag=&pageSize=&pageStart=   豆瓣热门/Top250
 *                              注意：tag=top250 时服务端忽略 type，一律返回「电影」Top250
 *   GET  /api/douban/categories?kind=&category=&type=&limit=&start=  豆瓣分类（电视剧可用）
 *   GET  /api/douban/recommends?kind=&limit=&start=&category=...    豆瓣推荐（LunaTV 网页版同款）
 *   GET  /api/favorites  /api/playrecords  /api/searchhistory         服务端收藏/进度/搜索历史
 *   GET  /api/live/sources /api/live/channels /api/live/epg            直播源/频道/节目单
 *   GET  /api/proxy/m3u8?url=&moontv-source=                          直播 m3u8 代理（重写分片地址）
 *
 * 鉴权：所有路由都要 auth cookie（middleware 拦截）。用 java.net.CookieManager 自动管理，
 * 登录成功时抓取 Set-Cookie 落 AuthHelper，冷启动再 seed 回来。
 */
public class MoontvApi {

    private static final String TAG = "moontv-api";
    public static final String BASE = MoontvApp.SITE_URL;

    private static final CookieManager CM = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    static {
        try { CookieHandler.setDefault(CM); } catch (Throwable t) { Log.w(TAG, "set cookie handler failed", t); }
    }

    /** 把本地保存的 auth cookie 注入 java.net CookieManager（冷启动后调用一次）。 */
    public static void seedAuthCookie(android.content.Context ctx) {
        String raw = AuthHelper.getSavedCookie(ctx);
        if (raw == null || raw.isEmpty()) return;
        try {
            // AuthHelper 存的是干净值（无 auth= 前缀），但兼容旧数据
            String val = raw.startsWith("auth=") ? raw.substring(5) : raw;
            int semi = val.indexOf(';');
            if (semi > 0) val = val.substring(0, semi);
            val = val.trim();
            if (val.isEmpty()) return;
            String host = URI.create(BASE).getHost();
            HttpCookie ck = new HttpCookie("auth", val);
            ck.setDomain(host);
            ck.setPath("/");
            ck.setVersion(0);
            CM.getCookieStore().add(new URI(BASE), ck);
            Log.i(TAG, "seed auth cookie -> " + host + " len=" + val.length());
        } catch (Throwable t) {
            Log.w(TAG, "seed cookie failed", t);
        }
    }

    /** 清空 cookie（退出登录）。 */
    public static void clearCookies() {
        try {
            CM.getCookieStore().removeAll();
        } catch (Throwable ignore) {
        }
    }

    /**
     * 取 auth cookie 的请求头值（"auth=xxx"），给不走 java.net CookieManager 的场景手动加头。
     *
     * 典型场景：直播频道台标要走 /api/proxy/logo（同样要鉴权），
     * 而 ImageLoader 是自己 new HttpURLConnection 的，不会自动带 cookie。
     */
    public static String authCookieHeader() {
        try {
            String v = AuthHelper.getSavedCookie(MoontvApp.getInstance());
            if (v == null || v.isEmpty()) return null;
            if (v.startsWith("auth=")) return v;
            return "auth=" + v;
        } catch (Throwable t) {
            return null;
        }
    }

    public static class AuthResult {
        public boolean ok;
        public String message;
    }

    /**
     * 登录（LunaTV 契约）。
     *
     * LunaTV 的 /api/login 有两种存储模式：
     *   - localstorage：只需要 password，username 忽略；
     *   - redis / upstash / kvrocks（老板的部署就是 redis，server-config 可查）：
     *     必须 username + password，且 username 要么是 env USERNAME（站长），
     *     要么是 config.UserConfig.Users 里的用户。
     *
     * 失败响应是 {ok:false, error:"..."}（注意是 error，不是旧版的 message），
     * 旧版客户端只读 message → 失败时提示信息为空，老板看不到原因。
     */
    public static AuthResult login(String username, String password) throws Exception {
        JSONObject body = new JSONObject();
        body.put("password", password == null ? "" : password);
        if (username != null && !username.isEmpty()) body.put("username", username);
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/api/login").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", MoontvApp.DEFAULT_UA);
        byte[] data = body.toString().getBytes("UTF-8");
        OutputStream os = c.getOutputStream();
        os.write(data);
        os.close();
        int code = c.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        String resp = readString(in);
        in.close();
        AuthResult r = new AuthResult();
        try {
            JSONObject o = new JSONObject(resp);
            r.ok = o.optBoolean("ok", false);
            // LunaTV 用 error 字段；兼容旧版 message
            String msg = o.optString("error", "");
            if (msg.isEmpty()) msg = o.optString("message", "");
            if (msg.isEmpty() && !r.ok && code >= 400) msg = "HTTP " + code;
            r.message = msg;
        } catch (Exception e) {
            r.ok = false;
            r.message = resp;
        }
        if (r.ok) {
            Map<String, List<String>> headers = c.getHeaderFields();
            List<String> sc = headers.get("Set-Cookie");
            if (sc != null) {
                for (String s : sc) {
                    if (s.startsWith("auth=")) {
                        AuthHelper.saveCookie(MoontvApp.getInstance(), s);
                        break;
                    }
                }
            }
        } else if (r.message != null && r.message.toLowerCase().contains("<!doctype html")) {
            r.message = "被安全防护拦截（返回网页而非 JSON）";
        }
        c.disconnect();
        return r;
    }

    /** 服务端配置（免鉴权）：SiteName / StorageType / Version。用来判断是 localstorage 还是 redis 模式。 */
    public static JSONObject serverConfig() throws Exception {
        return new JSONObject(get(BASE + "/api/server-config"));
    }

    public static void logout() throws Exception {
        try {
            post(BASE + "/api/logout", "{}", "application/json");
        } finally {
            AuthHelper.clear(MoontvApp.getInstance());
            try { CM.getCookieStore().removeAll(); } catch (Throwable ignore) {}
        }
    }

    public static ArrayList<SearchResult> search(String q) throws Exception {
        String resp = get(BASE + "/api/search?q=" + URLEncoder.encode(q, "UTF-8"));
        JSONObject o = new JSONObject(resp);
        JSONArray arr = o.optJSONArray("results");
        ArrayList<SearchResult> list = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);
                SearchResult r = new SearchResult();
                r.id = it.optString("id");
                r.title = it.optString("title");
                r.typeName = it.optString("type_name");
                r.poster = it.optString("poster");
                r.year = it.optString("year");
                r.source = it.optString("source");
                r.sourceName = it.optString("source_name");
                r.desc = it.optString("desc");
                // 关键：episodes 是 URL 字符串数组（可直接播放），不是对象数组
                readEpisodes(it.optJSONArray("episodes"), it.optJSONArray("episodes_titles"),
                        r.episodes, r.episodeTitles);
                list.add(r);
            }
        }
        return list;
    }

    /** 读取 episodes（string[]）与 episodes_titles（string[]）。 */
    private static void readEpisodes(JSONArray eps, JSONArray titles,
                                     ArrayList<String> outUrls, ArrayList<String> outTitles) {
        if (eps == null) return;
        for (int i = 0; i < eps.length(); i++) {
            String u = eps.optString(i, "");
            if (u == null || u.isEmpty()) continue;
            outUrls.add(u);
            String t = "";
            if (titles != null && i < titles.length()) t = titles.optString(i, "");
            if (t == null || t.isEmpty()) t = String.valueOf(i + 1);
            outTitles.add(t);
        }
    }

    public static Detail detail(String id, String source) throws Exception {
        String resp = get(BASE + "/api/detail?id=" + URLEncoder.encode(id, "UTF-8")
                + "&source=" + URLEncoder.encode(source, "UTF-8"));
        JSONObject o = new JSONObject(resp);
        Detail d = new Detail();
        d.id = o.optString("id", id);
        d.title = o.optString("title");
        d.poster = o.optString("poster");
        d.desc = o.optString("desc");
        d.year = o.optString("year");
        d.sourceName = o.optString("source_name");
        ArrayList<String> urls = new ArrayList<String>();
        ArrayList<String> titles = new ArrayList<String>();
        readEpisodes(o.optJSONArray("episodes"), o.optJSONArray("episodes_titles"), urls, titles);
        for (int i = 0; i < urls.size(); i++) {
            Episode ep = new Episode();
            ep.url = urls.get(i);
            ep.episode = titles.get(i);
            d.episodes.add(ep);
        }
        return d;
    }

    public static class DoubanPage {
        public int code = 200;
        public String message = "";
        public ArrayList<DoubanItem> list = new ArrayList<>();
    }

    public static DoubanPage douban(String type, String tag, int pageSize, int pageStart) throws Exception {
        String url = BASE + "/api/douban?type=" + URLEncoder.encode(type, "UTF-8")
                + "&tag=" + URLEncoder.encode(tag, "UTF-8")
                + "&pageSize=" + pageSize + "&pageStart=" + pageStart;
        String resp = get(url);
        JSONObject o = new JSONObject(resp);
        DoubanPage p = new DoubanPage();
        p.code = o.optInt("code", 200);
        p.message = o.optString("message");
        JSONArray arr = o.optJSONArray("list");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);
                DoubanItem d = new DoubanItem();
                d.id = it.optString("id");
                d.title = it.optString("title");
                d.poster = it.optString("poster");
                d.rate = it.optString("rate");
                d.year = it.optString("year");
                p.list.add(d);
            }
        }
        return p;
    }

    /**
     * 豆瓣分类（/api/douban/categories）。
     *
     * 与 /api/douban 用的是两套豆瓣接口：
     *   - /api/douban         → movie.douban.com/j/search_subjects?type=&tag=
     *                          【LunaTV 注意】tag=top250 时服务端走 handleTop250，
     *                          <b>完全忽略 type 参数</b>，一律返回「电影」Top250。
     *                          所以 type=tv&tag=top250 拿到的仍是电影，不能当剧集用。
     *   - /api/douban/categories → m.douban.com/rexxar/api/v2/subject/recent_hot/{kind}
     *                          电视剧可正常出数据（实测 kind=tv 有 58 条）
     *
     * @param kind     tv | movie
     * @param category 豆瓣分类（如 热门、经典、动作）
     * @param type     地区/类型筛选（可空）
     */
    public static DoubanPage doubanCategories(String kind, String category, String type,
                                               int pageSize, int pageStart) throws Exception {
        String url = BASE + "/api/douban/categories?kind=" + URLEncoder.encode(kind, "UTF-8")
                + "&category=" + URLEncoder.encode(category == null ? "" : category, "UTF-8")
                + "&type=" + URLEncoder.encode(type == null ? "" : type, "UTF-8")
                + "&limit=" + pageSize + "&start=" + pageStart;
        String resp = get(url);
        return parseDouban(resp);
    }

    /**
     * 豆瓣推荐（/api/douban/recommends）——LunaTV 网页版豆瓣页真正用的接口。
     * 底座是 m.douban.com/rexxar/api/v2/{kind}/recommend，
     * 返回 item.type 混合 movie/tv，服务端只保留这两类。
     *
     * 【实测结论】服务端把 category/format/region/year/platform/label 拼成
     * 豆瓣的 tags 参数，selected_categories 基本不生效：
     *   - year（tags=2025 / 2024 / 2026）→ <b>生效</b>，返回该年份的片子
     *   - 类型/地区（tags=喜剧 / 华语）→ 生效（作为豆瓣标签）
     *   - 但上游传的中文类目名（如"热门""最新"）在豆瓣端 <b>total=0</b>，不能直接用
     * 所以客户端只传「豆瓣认得的标签」：年份 + 英文类型/地区值。
     *
     * @param kind   movie | tv
     * @param limit  条数（1-100）
     * @param start  偏移
     * @param tags   豆瓣标签，逗号分隔（如 "2025" / "华语,2025" / "喜剧,2025"）；空表示不限
     */
    public static DoubanPage doubanRecommends(String kind, int limit, int start,
                                               String tags) throws Exception {
        StringBuilder sb = new StringBuilder(BASE + "/api/douban/recommends?kind=");
        sb.append(URLEncoder.encode(kind, "UTF-8"));
        sb.append("&limit=").append(limit).append("&start=").append(start);
        if (tags != null && !tags.isEmpty()) {
            // 服务端会把 category 之类的参数塞进 tags；这里直接传 tags 组合
            sb.append("&category=").append(URLEncoder.encode(tags, "UTF-8"));
        }
        String resp = get(sb.toString());
        return parseDouban(resp);
    }

    private static DoubanPage parseDouban(String resp) throws Exception {
        JSONObject o = new JSONObject(resp);
        DoubanPage p = new DoubanPage();
        p.code = o.optInt("code", 200);
        p.message = o.optString("message");
        JSONArray arr = o.optJSONArray("list");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);
                DoubanItem d = new DoubanItem();
                d.id = it.optString("id");
                d.title = it.optString("title");
                d.poster = it.optString("poster");
                d.rate = it.optString("rate");
                d.year = it.optString("year");
                p.list.add(d);
            }
        }
        return p;
    }

    /** 公开 GET（LunaStore 等复用）。 */
    public static String get(String url) throws Exception {
        return request("GET", url, null, null);
    }

    /** 公开 POST。 */
    public static String post(String url, String body, String contentType) throws Exception {
        return request("POST", url, body, contentType);
    }

    /** 公开 DELETE。 */
    public static String delete(String url) throws Exception {
        return request("DELETE", url, null, null);
    }

    /** 统一请求。失败时抛 IOException("HTTP code: body")。 */
    private static String request(String method, String url, String body, String contentType)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType == null ? "application/json" : contentType);
        }
        c.setRequestProperty("User-Agent", MoontvApp.DEFAULT_UA);
        c.setInstanceFollowRedirects(true);
        // 登记在途连接：切菜单时能立刻掐断正在下载的请求（老板要求"专心刷当前页面"）
        sPending = c;
        try {
            if (body != null) {
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
            }
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String s = readString(in);
            in.close();
            if (code < 200 || code >= 300) {
                throw new java.io.IOException("HTTP " + code + ": " + s);
            }
            return s;
        } finally {
            sPending = null;
            try { c.disconnect(); } catch (Throwable ignore) { }
        }
    }

    /**
     * 掐断当前在途请求（切菜单 / 换页时调用）。
     *
     * 【为什么要】老板要求"切换菜单后上一个页面没下载完也要停，专心刷当前页面"。
     * 光在 UI 层忽略返回结果没用 —— 线程还在把几十 MB 的数据拉下来，
     * 既占带宽又占内存（老设备上直接把堆撑爆）。
     */
    public static void abortPending() {
        HttpURLConnection c = sPending;
        sPending = null;
        if (c == null) return;
        try {
            c.disconnect();
            Log.i(TAG, "abort pending request");
        } catch (Throwable ignore) {
        }
    }

    /** 在途连接（供 abortPending 掐断）。 */
    private static volatile HttpURLConnection sPending;

    /** 原始响应（给 WebView 拦截代理请求用）。 */
    public static class Raw {
        public int code;
        public String mime = "";
        public byte[] data = new byte[0];
    }

    /**
     * 抓原始字节（带 auth cookie）。
     *
     * 【用途】直播播放地址必须走服务端代理 /api/proxy/m3u8，而该路由被 middleware 鉴权。
     * X5 内核的 cookie 行为不可靠（实测直播一直 hlserr:manifestLoadError，怀疑 cookie 没带上），
     * 所以在 WebViewClient.shouldInterceptRequest 里由 App 自己带 cookie 抓一次再喂给内核，
     * 这样彻底不依赖内核的 cookie 存储。
     */
    public static Raw fetchRaw(String url) {
        Raw r = new Raw();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setRequestProperty("User-Agent", MoontvApp.DEFAULT_UA);
            c.setInstanceFollowRedirects(true);
            if (url.startsWith(MoontvApp.SITE_URL)) {
                String ck = authCookieHeader();
                if (ck != null) c.setRequestProperty("Cookie", ck);
            }
            r.code = c.getResponseCode();
            r.mime = c.getContentType();
            InputStream in = (r.code >= 200 && r.code < 300) ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            try { in.close(); } catch (Throwable ignore) { }
            r.data = bo.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "fetchRaw failed: " + url + " " + t.getMessage());
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) { }
        }
        return r;
    }

    private static String readString(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), "UTF-8");
    }
}
