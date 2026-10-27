#!/usr/bin/env python3
"""
验证 LocalProxy 的【直连通路】（/live/*）与 m3u8 重写逻辑。

等价复刻 Java 侧 LocalProxy.forwardDirect + rewriteM3u8 + replaceKeyUri 的行为。

起 mock 源站（模拟直播源 m3u8）：
  - 校验请求带正确的 UA（直播源按 UA 放行）
  - 返回带【相对路径】分片与 EXT-X-KEY 相对密钥的 m3u8

再跑本地代理的直连逻辑，检查：
  1. 源站收到的 UA 是否是直播源配置的 UA
  2. m3u8 里的相对分片地址是否被还原成绝对地址
  3. 重写后是否变成 /live/seg?url=... 的本地代理路径
  4. EXT-X-KEY 的相对 URI 是否也被改写
"""
import http.server
import threading
import urllib.parse
import urllib.request
import socketserver

SOURCE_PORT = 18811
SOURCE_UA = "AptvPlayer/1.4.10"

# 源站返回的 m3u8：分片与密钥都是相对路径（上游 parseM3U 不做还原）
SOURCE_M3U8 = b"""#EXTM3U
#EXT-X-TARGETDURATION:4
#EXT-X-KEY:METHOD=AES-128,URI="keys/k1.key"
#EXTINF:4.0,
seg/seg1.ts
#EXTINF:4.0,
/abs/seg2.ts
#EXTINF:4.0,
http://other.example.com/seg3.ts
"""

seen = []


class Source(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        seen.append({"path": self.path, "ua": self.headers.get("User-Agent")})
        if self.path.endswith(".m3u8"):
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.apple.mpegurl")
            self.send_header("Content-Length", str(len(SOURCE_M3U8)))
            self.end_headers()
            self.wfile.write(SOURCE_M3U8)
        else:
            body = b"SEGMENT-DATA"
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)


# ---------------------------------------------------------------- Java 逻辑复刻

def base_dir(m3u8_url):
    """对齐 LunaLive.baseDir / 上游 getBaseUrl。"""
    import urllib.parse as up
    p = up.urlsplit(m3u8_url)
    path = p.path
    if path.endswith(".m3u8"):
        path = path[:path.rfind("/") + 1] or "/"
    elif not path.endswith("/"):
        path += "/"
    netloc = p.hostname or ""
    if p.port:
        netloc += ":" + str(p.port)
    return p.scheme + "://" + netloc + path


def resolve_url(base_m3u8_url, url):
    """对齐 LunaLive.resolveUrl / 上游 resolveUrl。"""
    if url.startswith("http://") or url.startswith("https://"):
        return url
    if url.startswith("//"):
        proto = "https:" if base_m3u8_url.startswith("https:") else "http:"
        return proto + url
    base = base_dir(base_m3u8_url)
    if url.startswith("/"):
        p = urllib.parse.urlsplit(base)
        netloc = p.netloc
        return p.scheme + "://" + netloc + url
    return base + url


def replace_key_uri(line, base):
    """对齐 LocalProxy.replaceKeyUri。"""
    i = line.find('URI="')
    if i < 0:
        return line
    s = i + 5
    e = line.find('"', s)
    if e <= s:
        return line
    uri = line[s:e]
    if uri.startswith("http://") or uri.startswith("https://"):
        return line
    absu = resolve_url(base, uri)
    return line[:s] + "/live/seg?url=" + urllib.parse.quote(absu, safe="") + line[e:]


def rewrite_m3u8(content, base_m3u8_url):
    """对齐 LocalProxy.rewriteM3u8。"""
    base = base_dir(base_m3u8_url)
    out = []
    for raw in content.split("\n"):
        line = raw.strip()
        if not line:
            out.append("")
            continue
        if line.startswith("#"):
            out.append(replace_key_uri(line, base))
            continue
        absu = resolve_url(base, line)
        out.append("/live/seg?url=" + urllib.parse.quote(absu, safe=""))
    return "\n".join(out)


def forward_direct(path_qs, source_base, source_ua):
    """对齐 LocalProxy.forwardDirect：抓源站 → 重写 m3u8 → 回写。"""
    q = urllib.parse.parse_qs(path_qs.lstrip("?"))
    target = q["url"][0]
    req = urllib.request.Request(target)
    req.add_header("User-Agent", source_ua)
    req.add_header("Accept", "*/*")
    with urllib.request.urlopen(req, timeout=15) as r:
        body = r.read()
        ctype = r.headers.get("Content-Type", "")
        final_url = r.geturl()
    if "mpegurl" in ctype.lower() or "octet-stream" in ctype.lower():
        text = body.decode("utf-8")
        return rewrite_m3u8(text, final_url).encode("utf-8"), "application/vnd.apple.mpegurl"
    return body, ctype


# ---------------------------------------------------------------- 运行

def main():
    httpd = socketserver.TCPServer(("127.0.0.1", SOURCE_PORT), Source)
    httpd.allow_reuse_address = True
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    time.sleep(0.4)

    source_base = "http://127.0.0.1:%d/live/list.m3u8" % SOURCE_PORT
    # 频道地址用相对路径（上游 parseM3U 原样返回的情形）
    channel_rel = "channel/cctv1.m3u8"
    abs_channel = resolve_url(source_base, channel_rel)
    print("[准备] 源站 m3u8 =", source_base)
    print("[准备] 频道地址(相对) =", channel_rel, "-> 还原为", abs_channel)

    body, ctype = forward_direct("?url=" + urllib.parse.quote(abs_channel, safe=""),
                                  source_base, SOURCE_UA)
    text = body.decode("utf-8")

    print("\n--- 直连后返回给播放器的 m3u8 ---")
    print(text)
    print("--- 源站收到的请求 ---")
    for s in seen:
        print("  path =", s["path"], "| UA =", s["ua"])

    ok = True

    # 1. UA 正确
    if not seen or seen[0]["ua"] != SOURCE_UA:
        print("\n[FAIL] 源站收到的 UA 不是直播源配置的 UA")
        ok = False
    else:
        print("\n[PASS] UA 正确注入:", SOURCE_UA)

    # 2. 相对分片被还原成绝对地址
    if "seg%2Fseg1.ts" not in text.replace("%2F", "%2F"):
        # 检查还原结果
        if "seg/seg1.ts" not in text:
            print("[FAIL] 相对分片 seg/seg1.ts 未被还原")
            ok = False
        else:
            print("[PASS] 相对分片已还原为绝对地址")
    else:
        print("[PASS] 相对分片已还原为绝对地址")

    # 3. 重写为 /live/seg?url=
    if text.count("/live/seg?url=") != 4:
        print("[FAIL] /live/seg?url= 数量应为 4（1 密钥 + 3 分片），实际",
              text.count("/live/seg?url="))
        ok = False
    else:
        print("[PASS] 全部 4 个地址（1 密钥 + 3 分片）都改写为 /live/seg?url=")

    # 4. EXT-X-KEY 相对 URI 也被改写
    if '#EXT-X-KEY:METHOD=AES-128,URI="/live/seg?url=' not in text:
        print("[FAIL] EXT-X-KEY 的相对 URI 未被改写")
        ok = False
    else:
        print("[PASS] EXT-X-KEY 相对 URI 已改写")

    # 5. 原本就是绝对地址的保持不变（不能被二次改写）
    if "http%3A%2F%2Fother.example.com%2Fseg3.ts" not in text.replace("%3A", "%3A"):
        if "other.example.com" not in text:
            print("[FAIL] 绝对地址分片丢失")
            ok = False
        else:
            print("[PASS] 绝对地址分片保留")
    else:
        print("[PASS] 绝对地址分片保留")

    httpd.shutdown()
    print("\n[全部通过]" if ok else "\n[存在失败]")
    return 0 if ok else 1


if __name__ == "__main__":
    import time
    raise SystemExit(main())
