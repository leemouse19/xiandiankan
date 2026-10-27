#!/usr/bin/env python3
"""
验证 LocalProxy 的转发逻辑（等价复刻 Java 侧的关键代码路径）。

起一个 mock 上游，模拟 LunaTV 的 /api/proxy/m3u8：
  - 校验请求带 auth cookie（否则 401）
  - 校验请求带 moontv-source（否则 404 Source not found）
  - 返回带相对路径分片的 m3u8

再起一个 mock 本地代理（复刻 LocalProxy.forward 的行为），
把请求转发到 mock 上游，检查：
  1. Cookie 头是否被带上
  2. moontv-source 是否被自动补上
  3. 响应体是否原样透传
"""
import http.server
import threading
import urllib.parse
import urllib.request
import socketserver

UPSTREAM_PORT = 18801
PROXY_PORT = 18802

M3U8_BODY = b"""#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080
/api/proxy/m3u8?url=http%3A%2F%2Fexample.com%2Fsub.m3u8
#EXTINF:4.0,
/api/proxy/segment?url=http%3A%2F%2Fexample.com%2Fseg1.ts
"""

seen = {}


class Upstream(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        p = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(p.query)
        seen[p.path] = {
            "cookie": self.headers.get("Cookie"),
            "source": q.get("moontv-source", [None])[0],
            "url": q.get("url", [None])[0],
            "ua": self.headers.get("User-Agent"),
        }
        if self.headers.get("Cookie") is None:
            self.send_response(401)
            self.end_headers()
            self.wfile.write(b"Unauthorized")
            return
        if not q.get("moontv-source"):
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b'{"error":"Source not found"}')
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.apple.mpegurl")
        self.send_header("Content-Length", str(len(M3U8_BODY)))
        self.end_headers()
        self.wfile.write(M3U8_BODY)


class Proxy(http.server.BaseHTTPRequestHandler):
    """复刻 LocalProxy：转发 /api/proxy/*，自动补 moontv-source，带 Cookie。"""

    SOURCE_KEY = "咪咕办公室"

    def log_message(self, *a):
        pass

    def do_GET(self):
        if not self.path.startswith("/api/proxy/"):
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b"not found")
            return
        path = self.path
        # injectSourceKey
        if "moontv-source=" not in path:
            sep = "&" if "?" in path else "?"
            path += sep + "moontv-source=" + urllib.parse.quote(self.SOURCE_KEY)
        # forward
        req = urllib.request.Request(
            "http://127.0.0.1:%d%s" % (UPSTREAM_PORT, path),
            headers={"Cookie": "auth=TESTCOOKIE", "User-Agent": "AptvPlayer/1.4.10"},
        )
        try:
            with urllib.request.urlopen(req) as r:
                body = r.read()
                ctype = r.headers.get("Content-Type", "application/octet-stream")
                code = r.status
        except urllib.error.HTTPError as e:
            body = e.read()
            ctype = "text/plain"
            code = e.code
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)


def serve(port, handler):
    class S(socketserver.ThreadingTCPServer):
        allow_reuse_address = True
        daemon_threads = True

    srv = S(("127.0.0.1", port), handler)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    return srv


def main():
    up = serve(UPSTREAM_PORT, Upstream)
    px = serve(PROXY_PORT, Proxy)
    try:
        # 模拟播放器页面：向本地代理请求 m3u8
        url = ("http://127.0.0.1:%d/api/proxy/m3u8?url=%s&moontv-source=%s"
               % (PROXY_PORT,
                  urllib.parse.quote("http://migu.example.com/626064703"),
                  urllib.parse.quote("咪咕办公室")))
        with urllib.request.urlopen(url) as r:
            body = r.read()
            print("[测试1] m3u8 顶层请求 http=%s len=%d" % (r.status, len(body)))

        # 模拟 hls.js 拉分片：服务端 rewrite 后的相对路径（丢了 moontv-source）
        seg = "http://127.0.0.1:%d/api/proxy/segment?url=%s" % (
            PROXY_PORT, urllib.parse.quote("http://migu.example.com/seg1.ts"))
        with urllib.request.urlopen(seg) as r:
            body = r.read()
            print("[测试2] 分片请求（无 moontv-source）http=%s len=%d" % (r.status, len(body)))

        print("\n--- 上游收到的请求 ---")
        for k, v in seen.items():
            print("  %s" % k)
            print("    Cookie        = %s" % v["cookie"])
            print("    moontv-source = %s" % v["source"])
            print("    url           = %s" % v["url"])
            print("    UA            = %s" % v["ua"])

        ok = True
        if seen.get("/api/proxy/m3u8", {}).get("cookie") != "auth=TESTCOOKIE":
            print("\n[失败] m3u8 请求没带 cookie")
            ok = False
        if seen.get("/api/proxy/segment", {}).get("source") != "咪咕办公室":
            print("\n[失败] 分片请求没补上 moontv-source")
            ok = False
        if seen.get("/api/proxy/m3u8", {}).get("source") != "咪咕办公室":
            print("\n[失败] m3u8 请求的 moontv-source 丢失")
            ok = False
        if b"EXT-X-STREAM-INF" not in body:
            print("\n[失败] 响应体没透传")
            ok = False

        print("\n%s" % ("[全部通过] 转发逻辑正确：cookie 带上 + moontv-source 补齐 + 响应透传"
                        if ok else "[存在失败项]"))
        return 0 if ok else 1
    finally:
        up.shutdown()
        px.shutdown()


if __name__ == "__main__":
    raise SystemExit(main())
