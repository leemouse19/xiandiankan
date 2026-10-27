package com.tvshell.moontv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简异步海报加载器（非 Gradle 环境不引 Glide/Picasso）。
 * 三级缓存：内存 LruCache + 磁盘文件缓存 + 网络下载（带采样降内存）。
 *
 * 【内存事故复盘 · 2026-10-26】
 * LANDI AECR C5 PRO（Android 7.1.2 / API 25）上出现 java.lang.OutOfMemoryError，
 * 堆栈落在 BitmapFactory.decodeFile → 本类的 load()。根因三条，全部已修：
 *   ① 磁盘缓存路径直接 decodeFile，**没有 inSampleSize**——按原图尺寸解码，
 *      一张海报就是几 MB；首页几十张直接爆堆。
 *   ② 使用 ARGB_8888（4 字节/像素）。海报是纯图片不需要 Alpha 通道，
 *      改 RGB_565（2 字节/像素）**内存直接减半**。
 *   ③ 目标尺寸按 360×540 定，实际显示只有 118dp×168dp（TV 上约 240×336 px），
 *      采样按 240×360 即可，内存再降到 1/2.25。
 * 另外把磁盘解码挪到后台线程——原先在主线程解码 40 张卡片，直接 ANR 风险。
 *
 * 磁盘缓存目录带版本号（img_v2）：老版本缓存里存的是未采样的原图，
 * 换目录后自动作废，避免旧大图继续撑爆堆。
 *
 * 豆瓣图片防盗链要点（实测）：
 *   img*.doubanio.com 不带 Referer 直接返回 HTTP 418（图片不显示）；
 *   带 Referer: https://movie.douban.com/ 才返回 200。
 * 因此这里对 doubanio.com 强制补 Referer；再失败则改走站点自身的 /api/image-proxy 兜底。
 */
public class ImageLoader {

    /** 内存缓存上限（MB）。低内存设备按 maxMemory 折算，见 mem()。 */
    private static final int MEM_MB = 16;
    /** 采样目标尺寸（px）。卡片显示 118dp×168dp，取这个尺寸足够清晰。 */
    private static final int TARGET_W = 240;
    private static final int TARGET_H = 360;
    /** 台标采样目标（px）：台标只 32dp 高，别按海报尺寸解。 */
    private static final int LOGO_W = 160;
    private static final int LOGO_H = 64;
    /** 磁盘缓存目录版本号：改动采样策略时 +1，旧缓存自动作废。 */
    private static final String DISK_DIR = "img_v2";
    private static final String TAG = "moontv-img";
    private static LruCache<String, Bitmap> sMem;
    private static final ExecutorService sPool = Executors.newFixedThreadPool(3);
    private static final Handler sUi = new Handler(Looper.getMainLooper());
    /** 代际号：切页面时 bumpGen()，让旧任务自行放弃。 */
    private static volatile int sGen = 0;

    private static synchronized LruCache<String, Bitmap> mem() {
        if (sMem == null) {
            int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
            int cacheKb = Math.max(1024, Math.min(MEM_MB * 1024, maxKb / 8));
            sMem = new LruCache<String, Bitmap>(cacheKb) {
                @Override
                protected int sizeOf(String k, Bitmap v) {
                    if (v == null || v.isRecycled()) return 0;
                    return v.getByteCount() / 1024;
                }
            };
        }
        return sMem;
    }

    /** 系统内存吃紧时清空内存缓存（配合 Application.onTrimMemory）。 */
    public static void clearMemory() {
        try {
            LruCache<String, Bitmap> c = sMem;
            if (c != null) c.evictAll();
        } catch (Throwable ignore) {
        }
    }

    private static File diskFile(Context ctx, String url) {
        String h = Integer.toHexString(url.hashCode());
        File dir = new File(ctx.getCacheDir(), DISK_DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "img_" + h + ".jpg");
    }

    public static void load(ImageView iv, String url, Context ctx) {
        loadSized(iv, url, ctx, TARGET_W, TARGET_H, false);
    }

    /**
     * 加载小图（直播台标等）。
     *
     * 【为什么要单独一档】台标只有 32dp 高，按海报的 240×360 采样纯属浪费：
     * 一张 172KB，128 个台标就是 22MB，老设备直接爆堆。
     * 按 160×64 采样后单张约 20KB，128 个全部常驻内存缓存，
     * 来回翻页零网络请求（磁盘缓存再兜一层）。
     */
    public static void loadLogo(ImageView iv, String url, Context ctx) {
        loadSized(iv, url, ctx, LOGO_W, LOGO_H, true);
    }

    /**
     * 当前代际号。
     *
     * 【代际机制】老板要求"切换菜单后上一个页面的下载要停，专心刷当前页面"。
     * 切菜单时 bumpGen() 让旧代际的在途/排队任务直接放弃（不再下载、不再贴图）。
     */
    public static int bumpGen() {
        sGen++;
        Log.i(TAG, "image gen -> " + sGen);
        return sGen;
    }

    private static void loadSized(ImageView iv, String url, Context ctx, final int tw, final int th, final boolean logLogo) {
        if (url == null || url.isEmpty() || ctx == null || iv == null) return;
        iv.setTag(R.id.img_loader_url, url);
        iv.setImageBitmap(null);
        Bitmap b = mem().get(url);
        if (b != null && !b.isRecycled()) {
            if (logLogo) Diag.i(TAG, "logo mem-hit: " + shortUrl(url));
            iv.setImageBitmap(b);
            return;
        }
        final int gen = sGen;
        // 磁盘解码也要采样，且必须放后台线程：40 张卡片在主线程 decodeFile 就是 ANR + 爆堆
        final Context appCtx = ctx.getApplicationContext();
        final ImageView target = iv;
        sPool.execute(new Runnable() {
            @Override
            public void run() {
                if (gen != sGen) return;      // 页面已切走 → 放弃
                Bitmap bm = null;
                try {
                    File f = diskFile(appCtx, url);
                    if (f.exists() && f.length() > 0) {
                        bm = decodeFileSampled(f, tw, th);
                    }
                    if (bm == null) {
                        if (gen != sGen) return;   // 下载前再确认一次，别白下
                        if (logLogo) Diag.i(TAG, "logo net-fetch: " + shortUrl(url));
                        bm = download(appCtx, url, tw, th);
                        if (bm != null) {
                            saveDisk(appCtx, url, bm);
                            if (logLogo) Diag.i(TAG, "logo disk-save: " + shortUrl(url));
                        }
                    } else if (logLogo) {
                        Diag.i(TAG, "logo disk-hit: " + shortUrl(url));
                    }
                } catch (OutOfMemoryError oom) {
                    // 单张图 OOM 不该拖垮整个 App
                    Log.w(TAG, "OOM on image: " + url);
                    clearMemory();
                    return;
                } catch (Throwable t) {
                    return;
                }
                if (bm == null || bm.isRecycled()) return;
                if (gen != sGen) return;      // 已切页 → 丢弃结果
                mem().put(url, bm);
                final Bitmap fb = bm;
                sUi.post(new Runnable() {
                    @Override
                    public void run() {
                        Object tag = target.getTag(R.id.img_loader_url);
                        if (tag != null && tag.equals(url) && fb != null && !fb.isRecycled()) {
                            target.setImageBitmap(fb);
                        }
                    }
                });
            }
        });
    }

    /** 台标 URL 缩短显示（只留域名+路径尾）。 */
    private static String shortUrl(String url) {
        if (url == null) return "null";
        int q = url.indexOf('?');
        String s = q >= 0 ? url.substring(0, q) : url;
        if (s.length() > 60) s = "..." + s.substring(s.length() - 57);
        return s;
    }

    /** 磁盘缓存解码：先读边界算 inSampleSize，再按目标尺寸解码（RGB_565）。 */
    private static Bitmap decodeFileSampled(File f, int tw, int th) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            o.inJustDecodeBounds = false;
            o.inSampleSize = calcSample(o.outWidth, o.outHeight, tw, th);
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (OutOfMemoryError oom) {
            Log.w(TAG, "OOM decoding disk cache: " + f.getName());
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 下载 + 失败回退（服务端 image-proxy）。 */
    private static Bitmap download(Context ctx, String url, int tw, int th) {
        byte[] data = fetch(url);
        if (data == null && url.contains("doubanio.com")) {
            // 兜底：走站点后端代理（后端带了正确的 Referer）
            try {
                String proxied = MoontvApp.SITE_URL + "/api/image-proxy?url="
                        + URLEncoder.encode(url, "UTF-8");
                data = fetch(proxied);
            } catch (Throwable ignore) {
            }
        }
        if (data == null) {
            Diag.e(TAG, "image load failed: " + url, null);
            return null;
        }
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int w = o.outWidth, h = o.outHeight;
            if (w <= 0 || h <= 0) return null;
            o.inJustDecodeBounds = false;
            o.inSampleSize = calcSample(w, h, tw, th);
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (OutOfMemoryError oom) {
            Log.w(TAG, "OOM decoding image: " + url);
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 计算采样倍数：把长边缩到目标尺寸附近，且取 2 的幂（解码更快）。 */
    private static int calcSample(int w, int h, int tw, int th) {
        int byW = w / tw;
        int byH = h / th;
        int s = Math.max(1, Math.min(byW, byH));
        // 归到 2 的幂
        int p = 1;
        while (p * 2 <= s) p *= 2;
        return p;
    }

    private static byte[] fetch(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", MoontvApp.DEFAULT_UA);
            c.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            // 站内图片代理（如直播台标 /api/proxy/logo）走 middleware 鉴权，必须手动带 auth cookie
            if (url.startsWith(MoontvApp.SITE_URL)) {
                String ck = MoontvApi.authCookieHeader();
                if (ck != null) c.setRequestProperty("Cookie", ck);
            }
            // 豆瓣图防盗链：必须带 Referer，否则 418
            if (url.contains("doubanio.com")) {
                c.setRequestProperty("Referer", "https://movie.douban.com/");
            }
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                Diag.e(TAG, "image http " + code + " : " + url, null);
                return null;
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "fetch failed", t);
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) { }
        }
    }

    private static void saveDisk(Context ctx, String url, Bitmap b) {
        try {
            File f = diskFile(ctx, url);
            FileOutputStream fo = new FileOutputStream(f);
            b.compress(Bitmap.CompressFormat.JPEG, 82, fo);
            fo.close();
        } catch (Throwable ignore) {
        }
    }
}
