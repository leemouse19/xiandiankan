package com.tvshell.moontv;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 直播源测速 + 分辨率探测。
 *
 * 【老板需求】"换源下面直接把能切换的源名称、速度、片源分辨率都显示出来，可以直接切"。
 * 上游 /api/live/sources 只给 key/name/url/ua，没有速度也没有分辨率，
 * 所以这里在客户端自己测：
 *
 *   速度   —— 走**服务端代理**拉一次该源的频道 m3u8，测端到端耗时（毫秒）。
 *             走代理而不是直连源站，因为真正播放时也是走代理，
 *             代理链路的耗时才是用户真正感受到的快慢。
 *   分辨率 —— 服务端重写 m3u8 时会保留 #EXT-X-STREAM-INF 标签，
 *             从里面解析 RESOLUTION=1920x1080 得到片源分辨率；
 *             媒体播放列表（没有 EXT-X-STREAM-INF）解析不到就显示"—"。
 */
public class SourceProbe {

    /** 探测结果。 */
    public static class Info {
        /** 端到端耗时（毫秒），-1 = 还没测/测失败。 */
        public int ms = -1;
        /** 分辨率，如 "1080P"；空 = 未知。 */
        public String resolution = "";
        /** 探测是否成功。 */
        public boolean ok;
        /** 下载字节数（m3u8 前 200KB）。 */
        public int bytes = 0;
        /** 吞吐量 KB/s（下载字节数 / 耗时）。 */
        public double kbps = 0;
    }

    private static final String TAG = "moontv-probe";

    /**
     * 探测某个直播源里某个频道的速度与分辨率。
     *
     * @param sourceKey  直播源 key（代理接口要带）
     * @param channelUrl 该频道的原始地址
     */
    public static Info probe(String sourceKey, String channelUrl) {
        Info info = new Info();
        if (channelUrl == null || channelUrl.isEmpty()) return info;
        String url = MoontvApp.SITE_URL + "/api/proxy/m3u8?url=" + enc(channelUrl)
                + "&moontv-source=" + enc(sourceKey);
        long t0 = System.currentTimeMillis();
        String body = null;
        try {
            body = MoontvApi.get(url);
        } catch (Throwable t) {
            Diag.e(TAG, "probe failed " + sourceKey + ": " + t.getMessage(), null);
            return info;
        }
        info.ms = (int) (System.currentTimeMillis() - t0);
        info.ok = true;
        info.resolution = parseResolution(body);
        Diag.i(TAG, "probe " + sourceKey + " ms=" + info.ms + " res=" + info.resolution);
        return info;
    }

    /**
     * 从 m3u8 文本里解析分辨率。
     *
     * 取所有 EXT-X-STREAM-INF 里的最大分辨率（主播放列表通常按清晰度从高到低排列）。
     */
    public static String parseResolution(String body) {
        if (body == null || body.isEmpty()) return "";
        int bestW = 0, bestH = 0;
        String[] lines = body.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i].trim();
            if (!ln.startsWith("#EXT-X-STREAM-INF")) continue;
            int p = ln.indexOf("RESOLUTION=");
            if (p < 0) continue;
            String v = ln.substring(p + 10);
            int end = v.indexOf(',');
            if (end < 0) end = v.indexOf(' ');
            if (end > 0) v = v.substring(0, end);
            v = v.trim();
            int x = v.indexOf('x');
            if (x <= 0) continue;
            try {
                int w = Integer.parseInt(v.substring(0, x));
                int h = Integer.parseInt(v.substring(x + 1));
                if (w > bestW) { bestW = w; bestH = h; }
            } catch (Throwable ignore) {
            }
        }
        if (bestW <= 0) return "";
        return bestH + "P";
    }

    /**
     * 直接探测一个 m3u8（点播线路用）：耗时 + 分辨率 + 吞吐量。
     *
     * 点播的播放地址是源站直链（不经过服务端代理），所以直接抓即可。
     * 抓的时候顺便算 KB/s——Web 版换源卡片上除了延迟还有吞吐量，
     * 老板看截图说"想在 App 里也这样显示"。
     */
    public static Info probeUrl(String url) {
        Info info = new Info();
        if (url == null || url.isEmpty()) return info;
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "AptvPlayer/1.4.10");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return info;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0 && total < 200000) {
                bo.write(buf, 0, n);
                total += n;
            }
            try { in.close(); } catch (Throwable ignore) { }
            long t1 = System.currentTimeMillis();
            info.ms = (int) (t1 - t0);
            info.bytes = total;
            if (total > 0 && (t1 - t0) > 0) {
                info.kbps = (double) total / (t1 - t0) / 1024.0 * 1000.0;
            }
            String body = new String(bo.toByteArray(), "UTF-8");
            info.ok = true;
            info.resolution = parseResolution(body);
            Diag.i(TAG, "probeUrl " + url + " ms=" + info.ms
                    + " kbps=" + String.format("%.1f", info.kbps)
                    + " res=" + info.resolution);
        } catch (Throwable t) {
            Diag.e(TAG, "probeUrl failed " + url + ": " + t.getMessage(), null);
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) { }
        }
        return info;
    }

    /** 展示用文案：吞吐量 + 延迟（Web 版换源卡片底部那行）。 */
    public static String describe(Info info) {
        if (info == null) return "测速中…";
        if (!info.ok) return "检测失败";
        StringBuilder sb = new StringBuilder();
        if (info.kbps > 0) {
            sb.append(String.format("%.1f KB/s", info.kbps));
        }
        if (info.ms >= 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(info.ms).append("ms");
        }
        if (sb.length() == 0) sb.append("—");
        return sb.toString();
    }

    /** 单独取分辨率文案（chip 右上角绿色高亮用）。 */
    public static String describeRes(Info info) {
        if (info == null) return "";
        if (!info.ok) return "检测失败";
        if (info.resolution == null || info.resolution.isEmpty()) return "未知";
        return info.resolution;
    }

    /**
     * 直连抓一段 m3u8（不走代理）——用于拿源站自己的分辨率信息。
     * 失败返回 null。
     */
    public static String fetchDirect(String url, String ua) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", ua == null || ua.isEmpty()
                    ? "AptvPlayer/1.4.10" : ua);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0 && total < 200000) {
                bo.write(buf, 0, n);
                total += n;
            }
            try { in.close(); } catch (Throwable ignore) { }
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) { }
        }
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }
}
