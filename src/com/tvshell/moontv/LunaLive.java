package com.tvshell.moontv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * LunaTV 直播模块（/api/live/*）。
 *
 * 【这是 LunaTV 相对旧版 MoonTV 独有的能力】老板的部署带直播源配置，
 * 旧契约里完全没有直播接口，所以之前没法做。现在按上游源码实现：
 *
 *   GET /api/live/sources            -> { success:true, data:[ LiveSource ] }
 *   GET /api/live/channels?source=KEY-> { success:true, data:[ LiveChannel ] }
 *   GET /api/live/epg?source=KEY&tvgId=TID
 *                                    -> { success:true, data:{ tvgId, source, epgUrl, programs:[...] } }
 *
 * 播放地址：直播源通常带 UA 限制且跨域，网页版走服务端代理
 *   GET /api/proxy/m3u8?url=<encoded>&moontv-source=KEY
 * 服务端会把 m3u8 里的分片地址重写成站内相对地址 /api/proxy/segment?url=...，
 * 并按直播源配置的 UA 抓取。因此我们的播放器必须以站点为 base 载入页面
 * （PlayerActivity 已经是 loadDataWithBaseURL(SITE_URL)），相对地址才能正确解析。
 */
public class LunaLive {

    private static final String TAG = "moontv-live";

    /** 直播源。 */
    public static class Source {
        public String key;
        public String name;
        public String url;
        public String ua;
        public String epg;
        public int channelNumber;
    }

    /** 频道。 */
    public static class Channel {
        public String id;
        public String tvgId;
        public String name;
        public String logo;
        public String group;
        public String url;
    }

    /** 节目单条目。 */
    public static class Program {
        public String start;
        public String end;
        public String title;
    }

    /** 拉取直播源列表。 */
    public static ArrayList<Source> sources() throws Exception {
        String resp = MoontvApi.get(MoontvApi.BASE + "/api/live/sources");
        JSONObject o = new JSONObject(resp);
        ArrayList<Source> out = new ArrayList<Source>();
        JSONArray arr = o.optJSONArray("data");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) continue;
                Source s = new Source();
                s.key = it.optString("key");
                s.name = it.optString("name");
                s.url = it.optString("url");
                s.ua = it.optString("ua");
                s.epg = it.optString("epg");
                s.channelNumber = it.optInt("channelNumber");
                out.add(s);
            }
        }
        return out;
    }

    /** 拉取某直播源的频道列表。 */
    public static ArrayList<Channel> channels(String sourceKey) throws Exception {
        String resp = MoontvApi.get(MoontvApi.BASE + "/api/live/channels?source="
                + enc(sourceKey));
        JSONObject o = new JSONObject(resp);
        ArrayList<Channel> out = new ArrayList<Channel>();
        JSONArray arr = o.optJSONArray("data");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) continue;
                Channel c = new Channel();
                c.id = it.optString("id");
                c.tvgId = it.optString("tvgId");
                c.name = it.optString("name");
                c.logo = it.optString("logo");
                c.group = it.optString("group");
                c.url = it.optString("url");
                out.add(c);
            }
        }
        return out;
    }

    /** 拉取某频道的节目单（EPG）。 */
    public static ArrayList<Program> epg(String sourceKey, String tvgId) throws Exception {
        String resp = MoontvApi.get(MoontvApi.BASE + "/api/live/epg?source="
                + enc(sourceKey) + "&tvgId=" + enc(tvgId));
        JSONObject o = new JSONObject(resp);
        ArrayList<Program> out = new ArrayList<Program>();
        JSONObject data = o.optJSONObject("data");
        if (data != null) {
            JSONArray arr = data.optJSONArray("programs");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it == null) continue;
                    Program p = new Program();
                    p.start = it.optString("start");
                    p.end = it.optString("end");
                    p.title = it.optString("title");
                    out.add(p);
                }
            }
        }
        return out;
    }

    /**
     * 生成直播播放地址（走服务端 m3u8 代理）。
     *
     * 服务端会用直播源配置的 UA 抓取原始 m3u8，并把分片重写成站内相对地址
     * /api/proxy/segment?url=... —— 这要求播放器页面以站点为 base。
     */
    public static String proxyUrl(String rawUrl, String sourceKey) {
        return MoontvApp.SITE_URL + "/api/proxy/m3u8?url=" + enc(rawUrl)
                + "&moontv-source=" + enc(sourceKey);
    }

    /**
     * 直播频道台标（走服务端代理）。
     *
     * 直播源 M3U 里的 tvg-logo 多为 http 明文地址，在 https 站点里直接引用会被
     * 浏览器/X5 的混合内容策略拦掉（台标空白）。上游网页版也是走 /api/proxy/logo，
     * 服务端会用直播源配置的 UA 去抓图。
     */
    public static String logoUrl(String logo, String sourceKey) {
        if (logo == null || logo.isEmpty()) return null;
        return MoontvApp.SITE_URL + "/api/proxy/logo?url=" + enc(logo)
                + "&source=" + enc(sourceKey);
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    /**
     * 取 m3u8 地址的目录 base（对齐上游 getBaseUrl）。
     *
     * 上游 rewriteM3U8Content 用它把 m3u8 里的相对路径还原成绝对地址，
     * 我们直连直播时也要做同样的事，否则相对分片地址无处可寻。
     */
    public static String baseDir(String m3u8Url) {
        if (m3u8Url == null || m3u8Url.isEmpty()) return "";
        try {
            java.net.URL u = new java.net.URL(m3u8Url);
            String path = u.getPath();
            if (path.endsWith(".m3u8")) {
                int i = path.lastIndexOf('/');
                path = i >= 0 ? path.substring(0, i + 1) : "/";
            } else if (!path.endsWith("/")) {
                path = path + "/";
            }
            return u.getProtocol() + "://" + u.getHost()
                    + (u.getPort() > 0 ? ":" + u.getPort() : "") + path;
        } catch (Throwable t) {
            return m3u8Url.endsWith("/") ? m3u8Url : m3u8Url + "/";
        }
    }

    /**
     * 把 m3u8 里的地址还原成绝对地址（对齐上游 resolveUrl）。
     *
     * 【为什么要自己还原】上游 parseM3U 解析频道列表时，把 #EXTINF 下一行的地址
     * 原样存进 channel.url，没有做相对地址还原（上游 resolveUrl 只在
     * rewriteM3U8Content 里用）。所以 /api/live/channels 返回的 url 可能是
     * "abc/1.m3u8" 这种相对路径。
     *
     * 走服务端代理时无所谓（服务端 fetch 会自己处理），
     * 但我们「直连模式」要在客户端拼出绝对地址，否则请求根本发不出去。
     */
    public static String resolveUrl(String baseM3u8Url, String url) {
        if (url == null || url.isEmpty()) return url;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (url.startsWith("//")) {
            String base = baseDir(baseM3u8Url);
            String proto = "http:";
            if (base.startsWith("https:")) proto = "https:";
            return proto + url;
        }
        String base = baseDir(baseM3u8Url);
        if (base.isEmpty()) return url;
        if (url.startsWith("/")) {
            try {
                java.net.URL b = new java.net.URL(base);
                return b.getProtocol() + "://" + b.getHost()
                        + (b.getPort() > 0 ? ":" + b.getPort() : "") + url;
            } catch (Throwable t) {
                return base + url;
            }
        }
        return base + url;
    }
}
