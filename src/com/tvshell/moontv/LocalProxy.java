package com.tvshell.moontv;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * App 内置的本地 HTTP 反向代理（直播专用）。
 *
 * 【为什么必须有它】直播播放地址是
 *     https://站点/api/proxy/m3u8?url=<原始流>&moontv-source=<源key>
 * 而 LunaTV 的 middleware 把除 api/login|api/register|api/logout|api/cron|api/server-config
 * 以外的路径全部纳入鉴权 —— /api/proxy/* 也必须带 auth cookie，否则 401 → 黑屏。
 *
 * 前一版依赖"把 cookie 塞进 X5 内核的 CookieManager"，实测 X5 内核的 cookie 行为不可靠：
 * 播放器仍然 hlserr:manifestLoadError（直播黑屏，老板反馈两次）。
 * 同一份代码里 App 侧 java.net 请求 /api/live/channels 却完全正常，
 * 说明 cookie 本身有效，只是**没被内核带出去**。
 *
 * 【本类的做法】在自己机器上开一个 127.0.0.1 的小 HTTP 服务：
 *   播放器页面以 http://127.0.0.1:<port>/ 为 base，
 *   页面里的 /api/proxy/... 请求全部落到本服务，
 *   本服务再带 auth cookie 转发到站点，拿到结果原样回给内核。
 * 这样：
 *   - 不依赖内核 cookie 存储（请求是我们自己发的，cookie 我们自己加）
 *   - 播放器与代理同源，不会有 CORS 问题
 *   - m3u8 / segment / key 任意层级都能透传，不受服务端 rewrite 细节影响
 *
 * 【资源开销】一条 accept 线程 + 固定 12 条工作线程；
 * 响应体用 8KB 缓冲流式转发，不整段进内存（分片可能几 MB）。
 */
public class LocalProxy {

    private static final String TAG = "moontv-lproxy";

    /** 站点根地址（转发目标）。 */
    private final String mSite;
    /** 工作线程数：hls.js 会并发拉多个分片，够用即可。 */
    private static final int POOL_SIZE = 12;

    private ServerSocket mSrv;
    private ExecutorService mPool;
    private volatile boolean mRunning = true;
    private final AtomicInteger mReqSeq = new AtomicInteger();
    /** 当前直播源 key：服务端所有 /api/proxy/* 路由都要靠它找直播源配置，缺失即 404。 */
    private volatile String mSourceKey = "";
    /** 当前直播源配置的 UA：直连模式从源站拉流时必须用它，否则源站拒连。 */
    private volatile String mSourceUa = "";
    /** 当前直播源清单 m3u8 地址：直连模式用来把频道的相对地址还原成绝对地址。 */
    private volatile String mSourceBase = "";

    /** 监听端口，0 表示启动失败（此时播放器退回站点直连模式）。 */
    private int mPort = 0;

    public LocalProxy(String site) {
        mSite = site.endsWith("/") ? site.substring(0, site.length() - 1) : site;
    }

    /** 设置直播源 key（切换源时要更新）。 */
    public void setSourceKey(String key) {
        mSourceKey = key == null ? "" : key;
    }

    /** 设置直播源的 UA 与清单地址（直连模式用）。 */
    public void setSourceInfo(String key, String ua, String baseM3u8) {
        mSourceKey = key == null ? "" : key;
        mSourceUa = (ua == null || ua.trim().isEmpty()) ? DEFAULT_LIVE_UA : ua.trim();
        mSourceBase = baseM3u8 == null ? "" : baseM3u8;
    }

    /** 上游默认 UA（对齐上游 live.ts 的 defaultUA）。 */
    private static final String DEFAULT_LIVE_UA = "AptvPlayer/1.4.10";

    /** 启动本地代理。返回端口，失败返回 0。 */
    public int start() {
        try {
            mSrv = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            mPort = mSrv.getLocalPort();
        } catch (Throwable t) {
            Log.w(TAG, "listen failed: " + t);
            Diag.e(TAG, "local proxy listen failed", t);
            mPort = 0;
            return 0;
        }
        mPool = Executors.newFixedThreadPool(POOL_SIZE);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "lproxy-accept");
        t.setDaemon(true);
        t.start();
        Diag.i(TAG, "local proxy started on 127.0.0.1:" + mPort);
        return mPort;
    }

    /** 播放器页面用的 base（带尾斜杠）。 */
    public String baseUrl() {
        return "http://127.0.0.1:" + mPort + "/";
    }

    public void stop() {
        mRunning = false;
        try {
            if (mSrv != null) mSrv.close();
        } catch (Throwable ignore) {
        }
        try {
            if (mPool != null) mPool.shutdownNow();
        } catch (Throwable ignore) {
        }
        Diag.i(TAG, "local proxy stopped");
    }

    private void acceptLoop() {
        while (mRunning) {
            final Socket sock;
            try {
                sock = mSrv.accept();
            } catch (Throwable t) {
                if (mRunning) Log.w(TAG, "accept failed: " + t);
                break;
            }
            try {
                mPool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(sock);
                    }
                });
            } catch (Throwable ignore) {
                closeQuietly(sock);
            }
        }
    }

    // ---------------------------------------------------------------- 单个请求

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(30000);
            InputStream rawIn = sock.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(rawIn, "ISO-8859-1"));
            OutputStream out = sock.getOutputStream();

            String reqLine = br.readLine();
            if (reqLine == null || reqLine.length() < 5) return;
            String[] parts = reqLine.split(" ");
            String method = parts[0];
            String path = parts[1];
            if (path == null || path.isEmpty()) return;

            // 读头
            Map<String, String> hdrs = new LinkedHashMap<String, String>();
            String line;
            while ((line = br.readLine()) != null && line.length() > 0) {
                int c = line.indexOf(':');
                if (c <= 0) continue;
                hdrs.put(line.substring(0, c).trim().toLowerCase(), line.substring(c + 1).trim());
            }

            // 只代理 GET；其它方法直接 405（播放器用不到）
            if (!"GET".equalsIgnoreCase(method)) {
                writeSimple(out, 405, "text/plain", "method not allowed");
                return;
            }
            // 两条通路：
            //  /api/proxy/* → 转发到站点（带 auth cookie，走服务端代理）
            //  /live/*     → 直连源站（用源配置 UA 抓流，完全绕开站点鉴权）
            boolean viaSite = path.startsWith("/api/proxy/");
            boolean direct = path.startsWith("/live/");
            if (!viaSite && !direct) {
                writeSimple(out, 404, "text/plain", "not found");
                return;
            }

            if (direct) {
                forwardDirect(out, path);
            } else {
                forward(out, path, hdrs);
            }
        } catch (Throwable t) {
            Log.w(TAG, "handle failed: " + t);
        } finally {
            closeQuietly(sock);
        }
    }

    /**
     * 直连源站抓流（不经站点代理）。
     *
     * 【为什么需要这条通路】站点 /api/proxy/* 必须带 auth cookie，
     * 而 X5 内核的 cookie jar 不可靠 → 直播黑屏（老板两次反馈）。
     * 既然 /api/live/sources 会把每个源的 ua 完整返给客户端，
     * 那我们完全可以自己拿着这个 UA 直接去源站抓流：
     *   - 不需要 cookie → 绕开站点鉴权
     *   - 请求是我们自己发的 → UA 精确可控
     *   - 分片地址在本地重写成 /live/seg?url=... → 播放器同源，无 CORS
     *
     * 路径形如 /live/m3u8?url=<encoded 原始流地址>
     * 分片形如 /live/seg?url=<encoded 分片绝对地址>
     */
    private void forwardDirect(OutputStream out, String path) {
        HttpURLConnection c = null;
        int seq = mReqSeq.incrementAndGet();
        try {
            Map<String, String> q = parseQuery(path);
            String target = q.get("url");
            if (target == null || target.isEmpty()) {
                writeSimple(out, 400, "text/plain", "missing url");
                return;
            }
            URL u = new URL(target);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(25000);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod("GET");
            // 关键：用直播源配置的 UA，源站认这个 UA 才放行
            c.setRequestProperty("User-Agent", mSourceUa);
            c.setRequestProperty("Accept", "*/*");

            int code = c.getResponseCode();
            String ctype = c.getContentType();
            if (ctype == null) ctype = guessMime(path);

            InputStream in = null;
            try {
                in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            } catch (Throwable ignore) {
            }

            // 响应是 m3u8 播放列表 → 读出来改写分片地址后整体回写
            boolean isPlaylist = ctype.toLowerCase().contains("mpegurl")
                    || ctype.toLowerCase().contains("octet-stream")
                    || path.contains("/live/m3u8");
            if (isPlaylist && in != null) {
                byte[] body = readAll(in, 512 * 1024);
                try { in.close(); } catch (Throwable ignore) { }
                String text = new String(body, "UTF-8");
                // 重定向后的最终地址才是相对地址的基准
                String finalUrl = c.getURL().toString();
                byte[] rewritten = rewriteM3u8(text, finalUrl).getBytes("UTF-8");
                writeHeaders(out, 200, "application/vnd.apple.mpegurl", rewritten.length);
                out.write(rewritten);
                out.flush();
                if (seq <= 8 || (code < 200 || code >= 300)) {
                    Diag.i(TAG, "direct# " + code + " " + (path.length() > 120 ? path.substring(0, 120) : path));
                }
                return;
            }

            // 其它（分片 ts / key）：原样流式回写
            long clen = c.getHeaderFieldLong("Content-Length", -1L);
            writeHeaders(out, code, ctype, clen);
            if (in != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                try { in.close(); } catch (Throwable ignore) { }
            }
            if (seq <= 8 || (code < 200 || code >= 300)) {
                Diag.i(TAG, "direct# " + code + " " + (path.length() > 120 ? path.substring(0, 120) : path));
            }
            if (code < 200 || code >= 300) {
                Diag.e(TAG, "direct# failed code=" + code + " path=" + path, null);
            }
        } catch (Throwable t) {
            Diag.e(TAG, "direct# error path=" + path, t);
            try {
                writeSimple(out, 502, "text/plain", "upstream error");
            } catch (Throwable ignore) {
            }
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) { }
        }
    }

    /**
     * 重写 m3u8 播放列表：把分片/子列表地址改成本代理的相对路径。
     *
     * 对齐上游 rewriteM3U8Content 的思路，但我们的目标是本地代理自己
     * （上游改的是站内 /api/proxy/segment）。改写后播放器与代理同源，
     * 不会被 CORS 拦。
     */
    private String rewriteM3u8(String content, String baseM3u8Url) {
        String base = LunaLive.baseDir(baseM3u8Url);
        StringBuilder sb = new StringBuilder();
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                sb.append("\n");
                continue;
            }
            if (line.startsWith("#")) {
                // EXT-X-KEY 里的 URI="..." 也要改（加密流的密钥）
                sb.append(replaceKeyUri(line, base)).append("\n");
                continue;
            }
            // 裸地址行 = 分片或子播放列表
            String abs = LunaLive.resolveUrl(base, line);
            sb.append("/live/seg?url=").append(urlEncode(abs)).append("\n");
        }
        return sb.toString();
    }

    /** 改写 #EXT-X-KEY:...URI="xxx" 里的相对地址。 */
    private String replaceKeyUri(String line, String base) {
        int i = line.indexOf("URI=\"");
        if (i < 0) return line;
        int s = i + 5;
        int e = line.indexOf('"', s);
        if (e <= s) return line;
        String uri = line.substring(s, e);
        if (uri.startsWith("http://") || uri.startsWith("https://")) return line;
        String abs = LunaLive.resolveUrl(base, uri);
        return line.substring(0, s) + "/live/seg?url=" + urlEncode(abs) + line.substring(e);
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    /** 读取请求的 query 参数。 */
    private static Map<String, String> parseQuery(String path) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        int q = path.indexOf('?');
        if (q < 0) return m;
        String qs = path.substring(q + 1);
        String[] parts = qs.split("&");
        for (int i = 0; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq < 0) continue;
            String k = parts[i].substring(0, eq);
            String v = parts[i].substring(eq + 1);
            try {
                m.put(java.net.URLDecoder.decode(k, "UTF-8"),
                        java.net.URLDecoder.decode(v, "UTF-8"));
            } catch (Exception e) {
                m.put(k, v);
            }
        }
        return m;
    }

    /** 读全部（限长，防超大响应吃内存）。 */
    private static byte[] readAll(InputStream in, int max) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            total += n;
            if (total >= max) break;
        }
        return bo.toByteArray();
    }

    /** 回写响应头（body 随后单独写）。 */
    private void writeHeaders(OutputStream out, int code, String ctype, long len) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
            sb.append("Content-Type: ").append(ctype).append("\r\n");
            if (len >= 0) sb.append("Content-Length: ").append(len).append("\r\n");
            sb.append("Access-Control-Allow-Origin: *\r\n");
            sb.append("Cache-Control: no-cache\r\n");
            sb.append("Connection: close\r\n");
            sb.append("\r\n");
            out.write(sb.toString().getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignore) {
        }
    }

    /** 转发到站点，带 auth cookie，响应流式回写。 */
    private void forward(OutputStream out, String path, Map<String, String> hdrs) {
        HttpURLConnection c = null;
        int seq = mReqSeq.incrementAndGet();
        try {
            URL u = new URL(mSite + injectSourceKey(path));
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(25000);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", MoontvApp.DEFAULT_UA);
            // 鉴权：站点 middleware 要求 /api/proxy/* 必须带 auth cookie
            String ck = MoontvApi.authCookieHeader();
            if (ck != null) c.setRequestProperty("Cookie", ck);
            // 透传 Range（部分源需要）
            String range = hdrs.get("range");
            if (range != null) c.setRequestProperty("Range", range);
            c.setRequestProperty("Referer", mSite + "/");
            c.setRequestProperty("Accept", "*/*");

            int code = c.getResponseCode();
            String ctype = c.getContentType();
            if (ctype == null) ctype = guessMime(path);
            // android.jar 没有 getLongHeaderField（那是 JDK11 的 API），用 getHeaderFieldLong
            long clen = c.getHeaderFieldLong("Content-Length", -1L);

            InputStream in = null;
            try {
                in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            } catch (Throwable ignore) {
            }

            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
            sb.append("Content-Type: ").append(ctype).append("\r\n");
            if (clen >= 0) sb.append("Content-Length: ").append(clen).append("\r\n");
            sb.append("Access-Control-Allow-Origin: *\r\n");
            sb.append("Cache-Control: no-cache\r\n");
            sb.append("Connection: close\r\n");
            sb.append("\r\n");
            out.write(sb.toString().getBytes("UTF-8"));
            out.flush();

            if (in != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                try { in.close(); } catch (Throwable ignore) { }
            }
            out.flush();
            // 只记前几个 + 失败的，避免刷爆日志
            if (seq <= 8 || (code < 200 || code >= 300)) {
                Diag.i(TAG, "proxy# " + code + " " + (path.length() > 120 ? path.substring(0, 120) : path));
            }
            if (code < 200 || code >= 300) {
                Diag.e(TAG, "proxy# failed code=" + code + " path=" + path, null);
            }
        } catch (Throwable t) {
            Diag.e(TAG, "proxy# error path=" + path, t);
            try {
                writeSimple(out, 502, "text/plain", "upstream error");
            } catch (Throwable ignore) {
            }
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) { }
        }
    }

    /**
     * 给转发请求补上 moontv-source 参数。
     *
     * 【为什么必须补】上游 rewriteM3U8Content 把分片改写成
     *     /api/proxy/segment?url=...
     * 把 moontv-source 丢了；而服务端 segment / key / 子 m3u8 路由都靠
     * moontv-source 去 LiveConfig 里找直播源配置（拿它的 ua），
     * 缺失直接 404 {"error":"Source not found"} → 直播拉不到流。
     * 这里在转发时统一补齐，页面侧就不必再操心这个参数。
     */
    private String injectSourceKey(String path) {
        String sk = mSourceKey;
        if (sk == null || sk.isEmpty()) return path;
        if (path.contains("moontv-source=")) return path;
        String enc;
        try {
            enc = java.net.URLEncoder.encode(sk, "UTF-8");
        } catch (Exception e) {
            enc = sk;
        }
        return path + (path.indexOf('?') >= 0 ? "&" : "?") + "moontv-source=" + enc;
    }

    private static String guessMime(String path) {
        String p = path.toLowerCase();
        if (p.contains("/m3u8")) return "application/vnd.apple.mpegurl";
        if (p.contains("/key")) return "application/octet-stream";
        if (p.contains("/logo")) return "image/jpeg";
        return "video/mp2t";
    }

    private static String reason(int code) {
        if (code == 200) return "OK";
        if (code == 401) return "Unauthorized";
        if (code == 403) return "Forbidden";
        if (code == 404) return "Not Found";
        if (code == 500) return "Internal Server Error";
        return "Status";
    }

    private static void writeSimple(OutputStream out, int code, String ctype, String body) {
        try {
            byte[] b = body.getBytes("UTF-8");
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
            sb.append("Content-Type: ").append(ctype).append("\r\n");
            sb.append("Content-Length: ").append(b.length).append("\r\n");
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes("UTF-8"));
            out.write(b);
            out.flush();
        } catch (Throwable ignore) {
        }
    }

    private static void closeQuietly(Socket s) {
        try { if (s != null) s.close(); } catch (Throwable ignore) { }
    }
}
