package com.tvshell.moontv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * 原生播放器（FR-03）：X5 只作为视频渲染面，其余（控制条 / 选集 / 进度 / 遥控）全部原生。
 *
 * 播放地址来自 /api/detail 的 episodes[].url（后端已解析完成）。
 * 页面用 assets/player.html（内联 hls.js），通过 loadDataWithBaseURL 以站点为 base 载入，
 * 规避 file:// 限制；HLS 优先走 X5 内核原生 HLS，失败再退回 hls.js。
 */
public class PlayerActivity extends Activity {

    public static final String EXTRA_ID = "id";
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_POSTER = "poster";
    public static final String EXTRA_EPISODES = "episodes";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_START_POS = "start_pos";

    /** 直播模式标记（LunaTV /api/live）。 */
    public static final String EXTRA_LIVE = "live";
    /** 直播频道原始地址（未经代理）。 */
    public static final String EXTRA_LIVE_URL = "live_url";
    /** 直播源 key（代理接口要带 moontv-source）。 */
    public static final String EXTRA_LIVE_SOURCE = "live_source";
    /** 直播源名称（显示用）。 */
    public static final String EXTRA_LIVE_SOURCE_NAME = "live_source_name";
    /** 直播频道名（频道列表里定位当前台）。 */
    public static final String EXTRA_LIVE_CHANNEL = "live_channel";
    /**
     * 直播源配置里的 UA（直连模式用）。
     *
     * 【为什么重要】上游 /api/live/sources 会把 LiveConfig 原样返回，
     * 里面每个源都带自己的 ua（缺省是 "AptvPlayer/1.4.10"）。
     * 直播源普遍按 UA 放行，用错 UA 直接被拒。
     * 走服务端代理时服务端会用这个 UA 去抓；
     * 我们自己直连源站时，也必须用同一个 UA。
     */
    public static final String EXTRA_LIVE_SOURCE_UA = "live_source_ua";
    /** 直播源的清单 m3u8 地址（直连时用来把频道的相对地址还原成绝对地址）。 */
    public static final String EXTRA_LIVE_SOURCE_BASE = "live_source_base";
    /** 点播的可切换线路（ArrayList&lt;SearchResult&gt;，播放器里换源用）。 */
    public static final String EXTRA_ALT_SOURCES = "alt_sources";

    private static final String TAG = "moontv-player";
    private static final int POLL_MS = 1000;
    private static final int HIDE_CONTROLS_MS = 5000;

    private TvWebView mWv;
    private FrameLayout mContainer;
    private View mControls;
    private View mTopBar;
    private SeekBar mSeek;
    private TextView mTime;
    private TextView mTitle;
    private Button mPlayPause;

    private final Handler mUi = new Handler(Looper.getMainLooper());
    private ArrayList<Episode> mEpisodes = new ArrayList<>();
    private int mIndex;
    private String mId;
    private String mSource;
    private String mTitleStr;
    private String mPosterUrl;
    /** 上次同步进度到服务端的时刻（节流用）。 */
    private long mLastCloudSync;

    private double mPos;
    private double mDur;
    /** 续播起点（秒）：从"继续观看"进入时使用。 */
    private long mStartPos;
    private boolean mPaused = true;
    private boolean mUserSeeking = false;
    private long mLastSave;
    private String mLastErr = "";
    private boolean mPageReady = false;
    private boolean mWebCreated = false;
    /** 是否直播模式。 */
    private boolean mLive = false;
    /** 直播频道原始地址。 */
    private String mLiveRawUrl;
    /** 直播源 key。 */
    private String mLiveSourceKey;
    /** 直播源名称。 */
    private String mLiveSourceName = "";
    /** 直播频道名。 */
    private String mLiveChannelName = "";
    /** 直播源配置里的 UA（直连模式抓流必须用）。 */
    private String mLiveSourceUa = "";
    /** 直播源清单 m3u8 地址（直连时还原相对地址用）。 */
    private String mLiveSourceBase = "";
    /**
     * 直播线路模式：
     *   MODE_SITE  = 走站点 /api/proxy/m3u8（服务端抓流，本地代理代发带 cookie）
     *   MODE_DIRECT= App 直连源站（本地代理带源 UA 抓，完全绕开站点鉴权）
     *   MODE_X5    = X5 内核原生 HLS 直连原始流（无代理、无 hls.js）
     */
    private static final int MODE_SITE = 0;
    private static final int MODE_DIRECT = 1;
    private static final int MODE_X5 = 2;
    /** 直播降级观察期：起播后该时长内的瞬时 hlserr 不触发降级（hls.js 会自行重试）。 */
    private static final long LIVE_FALLBACK_WINDOW_MS = 12000;
    private int mLiveMode = MODE_DIRECT; // 默认 App直连：已实测可播，绕开站点鉴权；SITE/X5 作自动降级兜底
    /** 最近一次起播时间戳，用于直播降级的最短观察期（避免慢启动被误判）。 */
    private long mPlayStartTs = 0;
    /** 当前线路模式名（显示给用户看）。 */
    private String mLiveModeName = "App直连";
    /** 是否已尝试过降级（避免无限重试）。 */
    private boolean mLiveTriedFallback = false;
    /** 点播可切换线路。 */
    private ArrayList<SearchResult> mAlts = new ArrayList<SearchResult>();
    /** 直播频道列表（频道面板用，按需加载后缓存）。 */
    private ArrayList<LunaLive.Channel> mLiveChannels = new ArrayList<LunaLive.Channel>();
    /** 直播源列表（换源面板用）。 */
    private ArrayList<LunaLive.Source> mLiveSources = new ArrayList<LunaLive.Source>();
    /** 当前正在计时的代理请求（避免刷屏）。 */
    private int mProxyLogTick = 0;
    /** App 内置本地 HTTP 代理（直播鉴权用，见 LocalProxy 的注释）。 */
    private LocalProxy mLocalProxy = null;

    private final Runnable mPoller = new Runnable() {
        @Override
        public void run() {
            pollState();
            mUi.postDelayed(this, POLL_MS);
        }
    };
    private final Runnable mHideControls = new Runnable() {
        @Override
        public void run() {
            if (!mUserSeeking) {
                mControls.setVisibility(View.GONE);
                mTopBar.setVisibility(View.GONE);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        ThemeManager.applyDarkChrome(this);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        mContainer = findViewById(R.id.player_container);
        mControls = findViewById(R.id.controls);
        mTopBar = findViewById(R.id.top_bar);
        mSeek = findViewById(R.id.seek);
        mTime = findViewById(R.id.time);
        mTitle = findViewById(R.id.player_title);
        mPlayPause = findViewById(R.id.btn_playpause);

        mId = getIntent().getStringExtra(EXTRA_ID);
        mSource = getIntent().getStringExtra(EXTRA_SOURCE);
        mTitleStr = getIntent().getStringExtra(EXTRA_TITLE);
        mPosterUrl = getIntent().getStringExtra(EXTRA_POSTER);
        mIndex = getIntent().getIntExtra(EXTRA_INDEX, 0);
        mStartPos = getIntent().getLongExtra(EXTRA_START_POS, 0);
        mLive = getIntent().getBooleanExtra(EXTRA_LIVE, false);
        mLiveRawUrl = getIntent().getStringExtra(EXTRA_LIVE_URL);
        mLiveSourceKey = getIntent().getStringExtra(EXTRA_LIVE_SOURCE);
        mLiveSourceName = getIntent().getStringExtra(EXTRA_LIVE_SOURCE_NAME);
        if (mLiveSourceName == null) mLiveSourceName = "";
        mLiveChannelName = getIntent().getStringExtra(EXTRA_LIVE_CHANNEL);
        if (mLiveChannelName == null) mLiveChannelName = "";
        mLiveSourceUa = getIntent().getStringExtra(EXTRA_LIVE_SOURCE_UA);
        if (mLiveSourceUa == null) mLiveSourceUa = "";
        mLiveSourceBase = getIntent().getStringExtra(EXTRA_LIVE_SOURCE_BASE);
        if (mLiveSourceBase == null) mLiveSourceBase = "";
        ArrayList<SearchResult> alts =
                (ArrayList<SearchResult>) getIntent().getSerializableExtra(EXTRA_ALT_SOURCES);
        if (alts != null) mAlts = alts;
        ArrayList<Episode> eps = (ArrayList<Episode>) getIntent().getSerializableExtra(EXTRA_EPISODES);
        if (eps != null) mEpisodes = eps;
        mTitle.setText(mTitleStr == null ? "" : mTitleStr);

        Button back = findViewById(R.id.btn_back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        mPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(); }
        });
        Button prev = findViewById(R.id.btn_prev);
        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchEpisode(mIndex - 1); }
        });
        Button next = findViewById(R.id.btn_next);
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchEpisode(mIndex + 1); }
        });
        Button btnEps = findViewById(R.id.btn_eps);
        btnEps.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showEpisodeDialog(); }
        });
        Button btnSrc = findViewById(R.id.btn_source);
        btnSrc.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSourceDialog(); }
        });
        Button btnLine = findViewById(R.id.btn_line);
        btnLine.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showLineDialog(); }
        });

        mSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar sb) { mUserSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                mUserSeeking = false;
                if (mDur > 0) {
                    double target = mDur * (sb.getProgress() / 1000.0);
                    js("window.MTPlay&&window.MTPlay.seek(" + target + ")");
                    Diag.i(TAG, "seek to " + (long) target);
                }
                scheduleHideControls();
            }
        });

        if (mLive) {
            // 直播模式：隐藏点播专属控件（上一集/下一集/选集/进度条）
            findViewById(R.id.btn_prev).setVisibility(View.GONE);
            findViewById(R.id.btn_next).setVisibility(View.GONE);
            findViewById(R.id.btn_eps).setVisibility(View.GONE);
            mSeek.setVisibility(View.GONE);
            mTime.setText("直播");
            // 直播显示「线路」按钮（站点代理 / App直连 / X5直连 手动切换）
            findViewById(R.id.btn_line).setVisibility(View.VISIBLE);
            ((Button) findViewById(R.id.btn_line)).setText("线路");
            if (mLiveRawUrl == null || mLiveRawUrl.isEmpty()) {
                Toast("直播地址为空");
                finish();
                return;
            }
            // 【直播鉴权】启动 App 内置本地代理。
            // 站点的 /api/proxy/* 必须带 auth cookie，而 X5 内核的 cookie 存储不可靠，
            // 表现为 hlserr:manifestLoadError（直播黑屏）。改由本代理代发请求。
            try {
                mLocalProxy = new LocalProxy(MoontvApp.SITE_URL);
                mLocalProxy.setSourceKey(mLiveSourceKey);
                int port = mLocalProxy.start();
                if (port <= 0) {
                    Diag.e(TAG, "local proxy unavailable, fallback to site base", null);
                    mLocalProxy = null;
                }
            } catch (Throwable t) {
                Diag.e(TAG, "local proxy start failed", t);
                mLocalProxy = null;
            }
        }

        if (!mLive && mEpisodes.isEmpty()) {
            Toast("没有可播放的剧集");
            finish();
            return;
        }

        setupWebView();
        showControls();
        mUi.postDelayed(mPoller, POLL_MS);
    }

    private void setupWebView() {
        if (X5KernelManager.get().isReady()) {
            createAndLoad();
            return;
        }
        // 内核还在预热：等它就绪再建 WebView（老设备的系统 WebView 播 HLS 兼容性差）
        Diag.i(TAG, "X5 not ready, waiting for kernel before creating player");
        X5KernelManager.get().init(this, new X5KernelManager.Callback() {
            @Override
            public void onResult(int status, String kernelVersion) {
                final int st = status;
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        Diag.i(TAG, "kernel callback status=" + st);
                        createAndLoad();
                    }
                });
            }
        });
        // 兜底：内核迟迟不就绪（最多 20s）就用系统 WebView 先顶上
        mUi.postDelayed(new Runnable() {
            @Override
            public void run() {
                Diag.i(TAG, "kernel wait timeout, create player anyway");
                createAndLoad();
            }
        }, 20000);
    }

    private void createAndLoad() {
        if (mWebCreated || isFinishing()) return;
        mWebCreated = true;
        mWv = createWebView();
        mWv.applyMoontvSettings(MoontvApp.DEFAULT_UA);
        // 【直播关键】把 auth cookie 写进 WebView 内核的 CookieManager。
        // 服务端 /api/proxy/* 同样被 middleware 拦截，缺 cookie 一律 401 → 直播黑屏。
        // （点播走的是 /api/detail 返回的裸 m3u8，不需要这一步，但一起写也无害。）
        try {
            String ck = MoontvApi.authCookieHeader();
            if (ck != null) {
                mWv.setCookie(MoontvApp.SITE_URL + "/", ck);
                Diag.i(TAG, "auth cookie injected into webview");
            }
        } catch (Throwable t) {
            Diag.e(TAG, "inject cookie failed", t);
        }
        mWv.setWebChromeClient(new TvWebChromeClient() {
            @Override
            public boolean onConsoleMessage(String level, String message, String sourceId, int lineNumber) {
                Diag.i("PLAYERJS", message);
                return true;
            }
        });
        mWv.setWebViewClient(new TvWebViewClient() {
            @Override
            public void onPageFinished(TvWebView view, String url) {
                Diag.i(TAG, "player page ready");
                mPageReady = true;
                playCurrent(0);
            }

            /**
             * 【直播黑屏的兜底拦截】
             * 直播播放地址必须走服务端代理 /api/proxy/m3u8，而该路由被 middleware 鉴权。
             * 主方案：页面 base 指向 App 内置本地代理（LocalProxy），
             *        由 App 带 auth cookie 发请求，不依赖内核 cookie 存储。
             * 这里保留拦截作为兜底（本地代理起不来时）：由 App 带 cookie 抓一次再喂给内核。
             */
            @Override
            public TvWebResp shouldInterceptRequest(TvWebView view, String url) {
                if (mLocalProxy != null) return null;
                return interceptProxy(url);
            }

            @Override
            public void onReceivedError(TvWebView view, int errorCode, String description, String failingUrl) {
                Diag.e(TAG, "player pageError code=" + errorCode + " url=" + failingUrl + " desc=" + description, null);
            }
        });

        View v = mWv.getView();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        v.setLayoutParams(lp);
        // 【老板需求】点屏幕：直播时点左侧出频道列表，其它地方点一下显示/隐藏底部菜单
        // ★ 修复：不能无条件 return true——那会把底部控制条按钮的触摸也一起吃掉，
        //   导致「换源」「线路」按钮永远点不了（触摸被 View.On(prod) 截胡）。
        //   判断触摸点是否落在控制条区域内：是则放行（让按钮自己处理），否则才当屏幕轻触。
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, android.view.MotionEvent event) {
                if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                    // ★ 先检查是否落在底部控制条范围内——是则放行给按钮处理
                    if (mControls.getVisibility() == View.VISIBLE) {
                        int[] loc = new int[2];
                        mControls.getLocationOnScreen(loc);
                        float absX = event.getRawX();
                        float absY = event.getRawY();
                        if (absX >= loc[0] && absX <= loc[0] + mControls.getWidth()
                                && absY >= loc[1] && absY <= loc[1] + mControls.getHeight()) {
                            return false; // 让控制条按钮自己处理
                        }
                    }
                    handleScreenTap(event.getX(), view.getWidth());
                }
                return true;
            }
        });
        mContainer.addView(v);

        String html = buildPlayerHtml();
        if (html == null) {
            Toast("播放器页面加载失败");
            finish();
            return;
        }
        mWv.loadDataWithBaseURL(pageBase(), html, "text/html", "UTF-8", null);
    }

    /**
     * 播放器页面的 base URL。
     *
     * 直播模式走 App 内置的本地代理（127.0.0.1），页面里所有 /api/proxy/* 请求
     * 都由本代理带 auth cookie 转发到站点 —— 不依赖 X5 内核的 cookie 存储
     * （实测不可靠，会导致直播 401 → hlserr:manifestLoadError → 黑屏）。
     * 点播模式不用代理，仍以站点为 base。
     */
    private String pageBase() {
        if (mLive && mLocalProxy != null) {
            String b = mLocalProxy.baseUrl();
            if (b != null && !b.isEmpty()) return b;
        }
        return MoontvApp.SITE_URL + "/";
    }

    /** 读取 assets/player.html，把 hls.min.js 内联进去，避免 file:// 子资源限制。 */
    private String buildPlayerHtml() {
        try {
            AssetManager am = getAssets();
            String page = readAsset(am, "player.html");
            String hls = readAsset(am, "hls.min.js");
            String tag = "<script src=\"hls.min.js\"></script>";
            if (page.contains(tag)) {
                page = page.replace(tag, "<script>" + hls + "</script>");
            }
            return page;
        } catch (Throwable t) {
            Diag.e(TAG, "build player html failed", t);
            return null;
        }
    }

    private static String readAsset(AssetManager am, String name) throws Exception {
        InputStream in = am.open(name);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private TvWebView createWebView() {
        if (X5KernelManager.get().isReady()) {
            try {
                return new X5WebView(this);
            } catch (Throwable t) {
                Log.e(TAG, "X5WebView create failed, fallback", t);
                Diag.e(TAG, "X5WebView create failed, fallback", t);
            }
        }
        return new SysWebView(new android.webkit.WebView(this));
    }

    private void playCurrent(long startPos) {
        if (mLive) {
            playLive();
            return;
        }
        if (mIndex < 0 || mIndex >= mEpisodes.size()) return;
        Episode ep = mEpisodes.get(mIndex);
        if (ep == null || ep.url == null || ep.url.isEmpty()) {
            Toast("该集没有播放地址");
            return;
        }
        Diag.i(TAG, "play idx=" + mIndex + " ep=" + ep.episode + " url=" + ep.url);
        final String url = ep.url;
        // 续播：优先用传入的起点
        long sp = startPos > 0 ? startPos : (mIndex == 0 ? mStartPos : 0);
        final long fs = sp;
        js("window.MTPlay&&window.MTPlay.load('" + url.replace("'", "\\'") + "')");
        if (fs > 0) {
            mUi.postDelayed(new Runnable() {
                @Override public void run() {
                    js("window.MTPlay&&window.MTPlay.seek(" + fs + ")");
                    Diag.i(TAG, "resume seek " + fs);
                }
            }, 1500);
        }
    }

    /**
     * 直播播放：三条线路，按优先级尝试。
     *
     * 【为什么有三条】老板问过「不用代理 X5 能不能播」，答案取决于直播源的
     * 防盗链策略，所以这里把三条路都实现，谁通用谁：
     *
     *   ① 站点代理（默认）
     *      本地代理 → 站点 /api/proxy/m3u8（带 auth cookie）
     *      服务端用直播源配置的 UA 抓流并重写分片。
     *      优点：与网页版行为完全一致，最稳；缺点：依赖站点代理路由可用。
     *
     *   ② App 直连源站
     *      本地代理 → 源站原始地址（带直播源自己的 UA），本地重写分片。
     *      优点：不经过站点鉴权，站点的 /api/proxy 挂了也能看；
     *      缺点：源站可能对出口 IP 有要求。
     *
     *   ③ X5 原生 HLS 直连
     *      播放器页面直接 <video src=原始地址>，X5 内核自己拉流。
     *      优点：完全不用我们的代理；缺点：内核 UA 全局，
     *      换 UA 会影响页面其它请求，且部分源认 UA 不认就直接拒。
     */
    private void playLive() {
        // 记录起播时间戳：降级观察期以它为基准，避免慢启动的瞬时 hlserr 误判
        mPlayStartTs = System.currentTimeMillis();
        // 直连模式要用绝对地址（上游解析频道列表时不做相对地址还原）
        String absUrl = LunaLive.resolveUrl(mLiveSourceBase, mLiveRawUrl);

        if (mLiveMode == MODE_X5) {
            // ③ X5 原生 HLS 直连：页面 base 仍指向本地代理（保持同源，
            // 免得 hls.js 回退时被 CORS 拦），但播放地址是原始流。
            if (mLocalProxy != null) {
                mLocalProxy.setSourceKey(mLiveSourceKey);
                mLocalProxy.setSourceInfo(mLiveSourceKey, mLiveSourceUa, mLiveSourceBase);
            }
            // 内核 UA 换成直播源自己的 UA（否则源站拒连）
            applyX5LiveUa();
            Diag.i(TAG, "play live [X5-native] url=" + absUrl);
            js("window.MTPlay&&window.MTPlay.load('" + absUrl.replace("'", "\\'") + "',1)");
            return;
        }

        // 非 X5 直连 → UA 改回默认（页面若要走站点 API 需要正常 UA）
        restoreDefaultUa();

        if (mLocalProxy == null) {
            // 本地代理没起来 → 退回站点直连（网页版同款地址）
            String url = LunaLive.proxyUrl(mLiveRawUrl, mLiveSourceKey);
            Diag.i(TAG, "play live [site-direct] url=" + url);
            js("window.MTPlay&&window.MTPlay.load('" + url.replace("'", "\\'") + "',1,'"
                    + (mLiveSourceKey == null ? "" : mLiveSourceKey.replace("'", "\\'")) + "')");
            return;
        }

        mLocalProxy.setSourceKey(mLiveSourceKey);
        mLocalProxy.setSourceInfo(mLiveSourceKey, mLiveSourceUa, mLiveSourceBase);

        String url;
        if (mLiveMode == MODE_DIRECT) {
            // ② App 直连源站：本地代理 /live/m3u8?url=<encoded>
            url = mLocalProxy.baseUrl() + "live/m3u8?url=" + enc(absUrl);
            Diag.i(TAG, "play live [app-direct] url=" + url);
        } else {
            // ① 站点代理：本地代理 /api/proxy/m3u8?url=...&moontv-source=...
            // 服务端返回的 m3u8 里分片是站内相对路径 /api/proxy/segment?url=...，
            // 因为页面 base 就是本地代理，这些相对地址同样会落到本地代理，
            // 于是 m3u8 / 分片 / key 全部带着鉴权信息发出。
            url = mLocalProxy.baseUrl() + "api/proxy/m3u8?url=" + enc(mLiveRawUrl)
                    + "&moontv-source=" + enc(mLiveSourceKey);
            Diag.i(TAG, "play live [site-proxy] url=" + url);
        }
        js("window.MTPlay&&window.MTPlay.load('" + url.replace("'", "\\'") + "',1,'"
                + (mLiveSourceKey == null ? "" : mLiveSourceKey.replace("'", "\\'")) + "')");
    }

    /**
     * 切换直播线路模式并重新播放。
     */
    private void switchLiveMode(int mode) {
        mLiveMode = mode;
        if (mode == MODE_SITE) {
            mLiveModeName = "站点代理";
        } else if (mode == MODE_DIRECT) {
            mLiveModeName = "App直连";
        } else {
            mLiveModeName = "X5直连";
        }
        Diag.i(TAG, "live mode -> " + mLiveModeName);
        Toast("线路：" + mLiveModeName);
        playLive();
    }

    /**
     * X5 原生 HLS 直连时，把内核 UA 换成直播源自己的 UA。
     *
     * 【为什么必须换】直播源普遍按 UA 放行（上游 defaultUA 是 "AptvPlayer/1.4.10"），
     * 用浏览器 UA 去拉，很多源直接 403/空响应。
     *
     * 【副作用】内核 UA 是全局的，改了会影响页面其它请求。
     * 但 X5 直连模式下页面本身只做播放（不走站点 API），
     * 所以影响可控；切回其它线路时立刻改回默认 UA。
     */
    private void applyX5LiveUa() {
        if (mWv == null) return;
        String ua = (mLiveSourceUa == null || mLiveSourceUa.trim().isEmpty())
                ? "AptvPlayer/1.4.10" : mLiveSourceUa.trim();
        try {
            mWv.setUserAgentString(ua);
            Diag.i(TAG, "x5 live UA set -> " + ua);
        } catch (Throwable t) {
            Diag.e(TAG, "set live UA failed", t);
        }
    }

    /** 恢复默认 UA（非 X5 直连模式时调用）。 */
    private void restoreDefaultUa() {
        if (mWv == null) return;
        try {
            String cur = mWv.getUserAgentString();
            if (cur != null && !cur.equals(MoontvApp.DEFAULT_UA)) {
                mWv.setUserAgentString(MoontvApp.DEFAULT_UA);
                Diag.i(TAG, "UA restored to default");
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * 直播起播失败时的自动降级：站点代理 → App 直连 → X5 直连。
     *
     * 触发点：播放器上报 hlserr 且画面没起来（readyState 一直为 0）。
     */
    private void tryLiveFallback(String err) {
        if (!mLive || mLiveTriedFallback) return;
        if (err == null || !err.startsWith("hlserr")) return;
        // 观察期：起播 12s 内的瞬时 hlserr（慢启动/首片超时）不降级，交给 hls.js 自行重试
        long since = System.currentTimeMillis() - mPlayStartTs;
        if (since < LIVE_FALLBACK_WINDOW_MS) {
            Diag.i(TAG, "live hlserr within observation window (" + since + "ms), skip fallback: " + err);
            return;
        }
        mLiveTriedFallback = true;
        int next;
        if (mLiveMode == MODE_SITE) {
            next = MODE_DIRECT;
        } else if (mLiveMode == MODE_DIRECT) {
            next = MODE_X5;
        } else {
            Diag.e(TAG, "live all modes failed: " + err, null);
            Toast("该直播源播放失败，试试换个源");
            return;
        }
        Diag.i(TAG, "live fallback: " + mLiveModeName + " -> " + next);
        switchLiveMode(next);
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    /**
     * 代理请求由 App 带 auth cookie 转发给内核。
     *
     * 只拦 /api/proxy/*（直播 m3u8 / 分片 / key / 台标），其它请求放行。
     */
    private TvWebResp interceptProxy(String url) {
        if (url == null) return null;
        if (!url.startsWith(MoontvApp.SITE_URL + "/api/proxy/")) return null;
        MoontvApi.Raw raw = MoontvApi.fetchRaw(url);
        mProxyLogTick++;
        // 分片很频繁，只在关键节点打日志，避免刷爆日志文件
        if (raw.code < 200 || raw.code >= 300 || mProxyLogTick <= 3) {
            Diag.i(TAG, "proxy http=" + raw.code + " len=" + raw.data.length + " " + shortUrl(url));
        }
        if (raw.code < 200 || raw.code >= 300) {
            Diag.e(TAG, "proxy failed http=" + raw.code + " body=" + snippet(raw.data), null);
            return new TvWebResp("text/plain", "UTF-8",
                    new java.io.ByteArrayInputStream(new byte[0]));
        }
        String mime = raw.mime;
        if (mime == null || mime.isEmpty()) mime = guessMime(url);
        String enc = mime.startsWith("text/") || mime.contains("mpegurl")
                || mime.contains("xml") || mime.contains("json") ? "UTF-8" : null;
        return new TvWebResp(mime, enc, new java.io.ByteArrayInputStream(raw.data));
    }

    private static String guessMime(String url) {
        String u = url.toLowerCase();
        if (u.contains("/m3u8")) return "application/vnd.apple.mpegurl";
        if (u.contains("/key")) return "application/octet-stream";
        if (u.contains("/logo")) return "image/jpeg";
        return "video/mp2t";
    }

    private static String shortUrl(String u) {
        if (u == null) return "";
        return u.length() <= 160 ? u : (u.substring(0, 160) + "...");
    }

    private static String snippet(byte[] b) {
        if (b == null || b.length == 0) return "(空)";
        int n = Math.min(b.length, 200);
        try {
            return new String(b, 0, n, "UTF-8");
        } catch (Throwable t) {
            return "(binary " + b.length + ")";
        }
    }

    /** 点屏幕：直播点左侧 → 频道列表；其它 → 显示/隐藏底部菜单。 */
    private void handleScreenTap(float x, int width) {
        if (mLive && width > 0 && x < width / 3f) {
            showChannelDialog();
            return;
        }
        if (mControls.getVisibility() == View.VISIBLE && mTopBar.getVisibility() == View.VISIBLE) {
            mControls.setVisibility(View.GONE);
            mTopBar.setVisibility(View.GONE);
            mUi.removeCallbacks(mHideControls);
        } else {
            showControls();
        }
    }

    /**
     * 直播频道列表（老板需求：直播播放界面按确认键 / 点屏幕左侧出频道列表）。
     */
    private void showChannelDialog() {
        if (!mLive) return;
        if (mLiveChannels.isEmpty()) {
            Toast("正在加载频道列表…");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ArrayList<LunaLive.Channel> list = null;
                    String err = null;
                    try {
                        list = LunaLive.channels(mLiveSourceKey);
                    } catch (Throwable t) {
                        err = t.getMessage();
                    }
                    final ArrayList<LunaLive.Channel> fl = list;
                    final String fe = err;
                    mUi.post(new Runnable() {
                        @Override
                        public void run() {
                            if (fe != null) {
                                Toast("频道列表加载失败：" + fe);
                                return;
                            }
                            if (fl == null || fl.isEmpty()) {
                                Toast("没有频道");
                                return;
                            }
                            mLiveChannels = fl;
                            showChannelDialogNow(fl);
                        }
                    });
                }
            }, "live-ch").start();
            return;
        }
        showChannelDialogNow(mLiveChannels);
    }

    private void showChannelDialogNow(ArrayList<LunaLive.Channel> list) {
        final String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            LunaLive.Channel c = list.get(i);
            String nm = c == null || c.name == null ? "" : c.name;
            labels[i] = nm.equals(mLiveChannelName) ? (nm + "  ✓") : nm;
        }
        new AlertDialog.Builder(this)
                .setTitle("频道列表（" + (mLiveSourceName == null || mLiveSourceName.isEmpty()
                        ? mLiveSourceKey : mLiveSourceName) + "）")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which < 0 || which >= list.size()) return;
                        switchLiveChannel(list.get(which));
                    }
                })
                .show();
    }

    /** 直播切台（同一源内）。 */
    private void switchLiveChannel(LunaLive.Channel c) {
        if (c == null || c.url == null || c.url.isEmpty()) return;
        mLiveRawUrl = c.url;
        mLiveChannelName = c.name == null ? "" : c.name;
        mTitle.setText(mLiveChannelName);
        // 换台后允许重新走一遍自动降级
        mLiveTriedFallback = false;
        mLastErr = "";
        Diag.i(TAG, "switch live channel -> " + mLiveChannelName);
        playLive();
        showControls();
    }

    /**
     * 线路选择（直播专用）：手动切换播放通路。
     *
     * 三条路的区别：
     *   站点代理 —— App 转发到站点 /api/proxy/m3u8（服务端抓流，等同网页版）
     *   App直连  —— App 拿着源自己的 UA 直接抓源站，绕开站点鉴权
     *   X5直连  —— 播放器页面用原生 <video> 直连原始流，完全不经我们的代理
     */
    private void showLineDialog() {
        if (!mLive) return;
        final String[] labels = {
            "站点代理（服务端抓流）" + (mLiveMode == MODE_SITE ? "  ✓" : ""),
            "App直连（绕开站点鉴权）" + (mLiveMode == MODE_DIRECT ? "  ✓" : ""),
            "X5直连（内核原生 HLS）" + (mLiveMode == MODE_X5 ? "  ✓" : "")
        };
        new AlertDialog.Builder(this)
                .setTitle("播放线路")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        mLiveTriedFallback = false;   // 手动切换后允许再次自动降级
                        if (which == 0) {
                            switchLiveMode(MODE_SITE);
                        } else if (which == 1) {
                            switchLiveMode(MODE_DIRECT);
                        } else {
                            switchLiveMode(MODE_X5);
                        }
                    }
                })
                .show();
    }

    /**
     * 换源面板（老板需求：播放界面底部菜单加换源，可直接切）。
     * 直播 → 换直播源（显示名称/速度/分辨率）；点播 → 换片源线路。
     */
    private void showSourceDialog() {
        if (mLive) {
            showLiveSourceDialog();
        } else {
            showVodSourceDialog();
        }
    }

    /** 直播换源：名称 + 速度 + 分辨率，选中即切到该源的同名频道。 */
    private void showLiveSourceDialog() {
        Toast("正在测速…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                ArrayList<LunaLive.Source> srcs = null;
                try {
                    srcs = LunaLive.sources();
                } catch (Throwable t) {
                    Diag.e(TAG, "live sources failed: " + t.getMessage(), null);
                }
                if (srcs == null || srcs.isEmpty()) {
                    mUi.post(new Runnable() {
                        @Override public void run() { Toast("没有其它直播源"); }
                    });
                    return;
                }
                mLiveSources = srcs;
                final ArrayList<String> labels = new ArrayList<String>();
                for (int i = 0; i < srcs.size(); i++) {
                    LunaLive.Source s = srcs.get(i);
                    SourceProbe.Info info = SourceProbe.probe(s.key, s.url);
                    String nm = (s.name == null || s.name.isEmpty()) ? s.key : s.name;
                    labels.add(nm + " · " + SourceProbe.describe(info));
                }
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        new AlertDialog.Builder(PlayerActivity.this)
                                .setTitle("换直播源")
                                .setItems(labels.toArray(new String[0]),
                                        new android.content.DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(android.content.DialogInterface d, int which) {
                                                if (which < 0 || which >= mLiveSources.size()) return;
                                                switchLiveSource(mLiveSources.get(which));
                                            }
                                        })
                                .show();
                    }
                });
            }
        }, "live-src").start();
    }

    /** 切直播源：在新源里找同名频道，找到就换过去继续播。 */
    private void switchLiveSource(final LunaLive.Source s) {
        if (s == null) return;
        Toast("正在切换到 " + (s.name == null ? s.key : s.name) + " …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                ArrayList<LunaLive.Channel> list = null;
                try {
                    list = LunaLive.channels(s.key);
                } catch (Throwable t) {
                    Diag.e(TAG, "switch source channels failed: " + t.getMessage(), null);
                }
                final ArrayList<LunaLive.Channel> fl = list;
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fl == null || fl.isEmpty()) {
                            Toast("该源没有频道");
                            return;
                        }
                        LunaLive.Channel pick = null;
                        for (int i = 0; i < fl.size(); i++) {
                            LunaLive.Channel c = fl.get(i);
                            if (c == null || c.name == null) continue;
                            if (c.name.equals(mLiveChannelName)) { pick = c; break; }
                        }
                        if (pick == null) pick = fl.get(0);
                        mLiveSourceKey = s.key;
                        mLiveSourceName = s.name == null ? s.key : s.name;
                        // 直连模式要用新源自己的 UA 和清单地址
                        mLiveSourceUa = s.ua == null ? "" : s.ua;
                        mLiveSourceBase = s.url == null ? "" : s.url;
                        mLiveChannels = fl;
                        // 本地代理要按新源找直播源配置（拿它的 ua），key 必须同步更新
                        if (mLocalProxy != null) {
                            mLocalProxy.setSourceKey(mLiveSourceKey);
                            mLocalProxy.setSourceInfo(mLiveSourceKey, mLiveSourceUa, mLiveSourceBase);
                        }
                        // 换了源，之前那条线路可能已经不通 → 允许重新降级
                        mLiveTriedFallback = false;
                        switchLiveChannel(pick);
                        Diag.i(TAG, "live source switched -> " + mLiveSourceKey + " ch=" + pick.name);
                    }
                });
            }
        }, "live-src-switch").start();
    }

    /** 点播换源：同片名的其它线路，带速度/分辨率。 */
    private void showVodSourceDialog() {
        if (mAlts == null || mAlts.isEmpty()) {
            Toast("没有其它线路");
            return;
        }
        Toast("正在测速…");
        final ArrayList<SearchResult> alts = new ArrayList<SearchResult>(mAlts);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ArrayList<String> labels = new ArrayList<String>();
                for (int i = 0; i < alts.size(); i++) {
                    SearchResult s = alts.get(i);
                    String nm = (s.sourceName == null || s.sourceName.isEmpty()) ? s.source : s.sourceName;
                    String url = (s.episodes != null && !s.episodes.isEmpty()) ? s.episodes.get(0) : null;
                    SourceProbe.Info info = SourceProbe.probeUrl(url);
                    labels.add(nm + " · " + s.episodes.size() + "集 · " + SourceProbe.describe(info));
                }
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        new AlertDialog.Builder(PlayerActivity.this)
                                .setTitle("换源")
                                .setItems(labels.toArray(new String[0]),
                                        new android.content.DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(android.content.DialogInterface d, int which) {
                                                if (which < 0 || which >= alts.size()) return;
                                                switchVodSource(alts.get(which));
                                            }
                                        })
                                .show();
                    }
                });
            }
        }, "vod-src").start();
    }

    /** 点播切线路：换集数列表并从头播。 */
    private void switchVodSource(SearchResult s) {
        if (s == null) return;
        ArrayList<Episode> eps = new ArrayList<Episode>();
        for (int i = 0; i < s.episodes.size(); i++) {
            Episode ep = new Episode();
            ep.url = s.episodes.get(i);
            ep.episode = (i < s.episodeTitles.size() && s.episodeTitles.get(i) != null
                    && !s.episodeTitles.get(i).isEmpty()) ? s.episodeTitles.get(i) : String.valueOf(i + 1);
            eps.add(ep);
        }
        if (eps.isEmpty()) {
            Toast("该线路没有可播剧集");
            return;
        }
        mEpisodes = eps;
        mIndex = 0;
        mId = s.id;
        mSource = s.source;
        mPos = 0;
        mDur = 0;
        Diag.i(TAG, "vod source switched -> " + s.source + "/" + s.id + " eps=" + eps.size());
        Toast("已切换到 " + (s.sourceName == null ? s.source : s.sourceName));
        playCurrent(0);
        showControls();
    }

    private void switchEpisode(int idx) {
        if (idx < 0 || idx >= mEpisodes.size()) {
            Toast("没有更多剧集");
            return;
        }
        mIndex = idx;
        mPos = 0;
        saveProgress(true);
        playCurrent(0);
        showControls();
    }

    private void showEpisodeDialog() {
        final String[] labels = new String[mEpisodes.size()];
        for (int i = 0; i < mEpisodes.size(); i++) {
            Episode e = mEpisodes.get(i);
            labels[i] = (e.episode == null || e.episode.isEmpty() ? String.valueOf(i + 1) : e.episode)
                    + (i == mIndex ? "  ✓" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("选集")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        switchEpisode(which);
                    }
                })
                .show();
    }

    private void togglePlay() {
        js("window.MTPlay&&(window.MTPlay.isPaused()?window.MTPlay.play():window.MTPlay.pause())");
        Diag.i(TAG, "toggle play/pause");
        scheduleHideControls();
    }

    private void js(String code) {
        if (mWv == null) return;
        try {
            mWv.evaluateJavascript(code, null);
        } catch (Throwable t) {
            Log.w(TAG, "js failed", t);
        }
    }

    private void pollState() {
        if (mWv == null || !mPageReady) return;
        mWv.evaluateJavascript("window.MTState?window.MTState():''", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (value == null) return;
                applyState(unescape(value));
            }
        });
    }

    private void applyState(String state) {
        if (state == null || state.isEmpty()) return;
        String[] p = state.split("\\|");
        if (p.length < 4) return;
        try {
            mPos = Double.parseDouble(p[0]);
            mDur = Double.parseDouble(p[1]);
            mPaused = "1".equals(p[2]);
            String err = p[3];
            if (!err.isEmpty()) {
                // 每次轮询都进降级判断（内部已处理观察期/是否已降级/错误去重日志）
                if (!err.equals(mLastErr)) {
                    mLastErr = err;
                    Diag.e(TAG, "play error: " + err, null);
                }
                // 直播起播失败 → 自动换一条线路重试（观察期内不降，避免慢启动误判）
                tryLiveFallback(err);
            } else {
                mLastErr = "";
            }
            if (p.length >= 6 && !p[5].isEmpty()) {
                // src 变了（换集）无需处理，这里只留日志
            }
        } catch (Exception e) {
            return;
        }
        if (!mUserSeeking && mDur > 0) {
            int prog = (int) (mPos * 1000 / mDur);
            if (prog < 0) prog = 0;
            if (prog > 1000) prog = 1000;
            mSeek.setProgress(prog);
        }
        if (!mLive) mTime.setText(fmt(mPos) + " / " + fmt(mDur));
        mPlayPause.setText(mPaused ? "播放" : "暂停");
        if (!mLive && System.currentTimeMillis() - mLastSave > 5000) {
            saveProgress(false);
        }
    }

    /**
     * 保存播放进度。
     *
     * 本地 Store 立即写（保证 App 内秒级可用），同时异步写服务端 redis，
     * 这样网页版的「继续观看」和 App 是同一份数据（多端同步）。
     * 服务端写入做节流：每 10 秒最多一次，退出时强制写一次。
     */
    private void saveProgress(boolean force) {
        if (mLive) return;   // 直播没有进度，也不该污染「继续观看」
        if (mId == null || mSource == null) return;
        if (mEpisodes.isEmpty() || mIndex < 0 || mIndex >= mEpisodes.size()) return;
        if (!force && mDur <= 0) return;
        Store.Progress p = new Store.Progress();
        p.source = mSource;
        p.id = mId;
        p.title = mTitleStr;
        p.poster = mPosterUrl;
        p.episodeIndex = mIndex;
        p.position = (long) mPos;
        p.duration = (long) mDur;
        p.ts = System.currentTimeMillis();
        Store.savePlayProgress(this, p);
        if (force) Diag.i(TAG, "progress saved idx=" + mIndex + " pos=" + (long) mPos);
        syncProgressToCloud(force);
    }

    /** 播放进度同步到服务端（节流）。 */
    private void syncProgressToCloud(boolean force) {
        long now = System.currentTimeMillis();
        if (!force) {
            if (now - mLastCloudSync < 10000) return;
        }
        mLastCloudSync = now;
        final String sid = mId, ssrc = mSource;
        final String title = mTitleStr == null ? "" : mTitleStr;
        final String cover = mPosterUrl == null ? "" : mPosterUrl;
        final int index = mIndex;
        final int total = mEpisodes.size();
        final long play = (long) mPos;
        final long dur = (long) mDur;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    LunaStore.savePlayRecord(ssrc, sid, title, cover, "", ssrc,
                            index + 1, total, play, dur, title);
                    if (force) Diag.i(TAG, "cloud progress synced " + ssrc + "/" + sid);
                } catch (Throwable t) {
                    Diag.e(TAG, "cloud progress failed: " + t.getMessage(), null);
                }
            }
        }, "progress-sync").start();
    }

    private static String fmt(double sec) {
        if (sec < 0) sec = 0;
        long s = (long) sec;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long ss = s % 60;
        if (h > 0) return String.format("%02d:%02d:%02d", h, m, ss);
        return String.format("%02d:%02d", m, ss);
    }

    private static String unescape(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1);
        }
        t = t.replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\");
        return t;
    }

    private void showControls() {
        mControls.setVisibility(View.VISIBLE);
        mTopBar.setVisibility(View.VISIBLE);
        scheduleHideControls();
    }

    private void scheduleHideControls() {
        mUi.removeCallbacks(mHideControls);
        mUi.postDelayed(mHideControls, HIDE_CONTROLS_MS);
    }

    private void hideSystemUi() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    /** 遥控：左右快进快退、上下切控制条、确认键切播放/暂停、返回退出。 */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int k = event.getKeyCode();

            // ===== 直播模式：确认键出频道列表；上下键调出底部菜单；换台键直接换台 =====
            if (mLive) {
                View f = getCurrentFocus();
                boolean focusOnControl = mControls.getVisibility() == View.VISIBLE && isFocusOnControl();
                if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                    // 焦点已经在控制条按钮上 → 先执行按钮（换源/播放暂停）
                    if (focusOnControl && f != null && (mWv == null || f != mWv.getView())
                            && f.performClick()) {
                        return true;
                    }
                    // 否则出频道列表（老板需求）
                    showChannelDialog();
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (mControls.getVisibility() != View.VISIBLE) {
                        showControls();
                        focusFirstControl();
                        return true;
                    }
                    return super.dispatchKeyEvent(event);
                }
                if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (focusOnControl) return super.dispatchKeyEvent(event);
                    return true;
                }
                if (k == KeyEvent.KEYCODE_CHANNEL_UP || k == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                    stepChannel(k == KeyEvent.KEYCODE_CHANNEL_UP ? -1 : 1);
                    return true;
                }
            }

            if (k == KeyEvent.KEYCODE_DPAD_CENTER) {
                // 控制条可见且焦点在按钮上 → 直接触发该按钮
                if (mControls.getVisibility() == View.VISIBLE) {
                    View f = getCurrentFocus();
                    // mWv 可能还没建好（内核预热中），此时不能取 getView()
                    if (f != null && (mWv == null || f != mWv.getView()) && f.performClick()) {
                        return true;
                    }
                }
                // 否则弹出控制条并把焦点给到「播放/暂停」
                showControls();
                mPlayPause.requestFocus();
                return true;
            }
            // 焦点在控制条按钮上时，方向键用于移动焦点（不抢去 seek）
            boolean focusOnControl = mControls.getVisibility() == View.VISIBLE
                    && isFocusOnControl();
            if (focusOnControl && (k == KeyEvent.KEYCODE_DPAD_LEFT
                    || k == KeyEvent.KEYCODE_DPAD_RIGHT
                    || k == KeyEvent.KEYCODE_DPAD_UP
                    || k == KeyEvent.KEYCODE_DPAD_DOWN)) {
                return super.dispatchKeyEvent(event);
            }
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (!mLive) seekBy(-10);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (!mLive) seekBy(10);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                showControls();
                return true;
            }
            if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    || k == KeyEvent.KEYCODE_MEDIA_PLAY
                    || k == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                togglePlay();
                return true;
            }
            if (k == KeyEvent.KEYCODE_BACK) {
                saveProgress(true);
                finish();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** 当前焦点是否落在控制条里的按钮上。 */
    private boolean isFocusOnControl() {
        View f = getCurrentFocus();
        if (f == null) return false;
        android.view.ViewParent p = f.getParent();
        while (p != null) {
            if (p == mControls) return true;
            p = p.getParent();
        }
        return false;
    }

    /** 焦点落到控制条的第一个可用按钮上（遥控上下键调出菜单后用）。 */
    private void focusFirstControl() {
        if (!(mControls instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) mControls;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == null || c.getVisibility() != View.VISIBLE) continue;
            if (c instanceof android.widget.SeekBar) continue;
            c.requestFocus();
            return;
        }
    }

    /** 直播换台键：上/下一个频道。 */
    private void stepChannel(int dir) {
        if (!mLive) return;
        if (mLiveChannels.isEmpty()) {
            showChannelDialog();
            return;
        }
        int idx = -1;
        for (int i = 0; i < mLiveChannels.size(); i++) {
            LunaLive.Channel c = mLiveChannels.get(i);
            if (c != null && c.name != null && c.name.equals(mLiveChannelName)) { idx = i; break; }
        }
        int next = (idx < 0) ? 0 : idx + dir;
        if (next < 0 || next >= mLiveChannels.size()) return;
        switchLiveChannel(mLiveChannels.get(next));
    }

    private void seekBy(int deltaSec) {
        if (mDur <= 0) {
            Toast("尚未开始播放");
            return;
        }
        double target = mPos + deltaSec;
        if (target < 0) target = 0;
        if (target > mDur) target = mDur;
        js("window.MTPlay&&window.MTPlay.seek(" + target + ")");
        Diag.i(TAG, "remote seek " + (long) target);
        showControls();
    }

    private void Toast(String s) {
        try {
            android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignore) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Diag.i(TAG, "onPause");
        saveProgress(true);
        Diag.dumpLogcat();
        if (mWv != null) mWv.onPause();
        mUi.removeCallbacks(mPoller);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWv != null) mWv.onResume();
        mUi.removeCallbacks(mPoller);
        mUi.postDelayed(mPoller, POLL_MS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mUi.removeCallbacksAndMessages(null);
        if (mWv != null) {
            View v = mWv.getView();
            if (v != null && v.getParent() instanceof ViewGroup) {
                ((ViewGroup) v.getParent()).removeView(v);
            }
            mWv.destroy();
            mWv = null;
        }
        mContainer = null;
        // 释放本地代理的监听端口与线程
        if (mLocalProxy != null) {
            mLocalProxy.stop();
            mLocalProxy = null;
        }
    }
}
