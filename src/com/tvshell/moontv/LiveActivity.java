package com.tvshell.moontv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * 原生直播页（LunaTV 独有能力）。
 *
 * 对应上游 LunaTV 的 /live 页面，但完全用原生 UI 实现：
 *   /api/live/sources             -> 直播源（横向切换）
 *   /api/live/channels?source=KEY -> 该源的频道列表（网格）
 *   /api/live/epg?source&tvgId    -> 当前频道的节目单（底部横滑）
 *
 * 播放：直播源 M3U 里的地址往往带 UA 限制且跨域，必须走服务端代理
 *   /api/proxy/m3u8?url=...&moontv-source=KEY
 * 服务端会用直播源配置的 UA 抓流，并把分片重写成站内相对地址，
 * 所以播放器页面要以站点为 base 载入（PlayerActivity 已如此），
 * 同时 WebView 内核必须带上 auth cookie（代理路由同样被 middleware 拦截）。
 *
 * 【遥控导航为什么自己管】★ 本项目踩过的最大的坑：
 *   GridView/AdapterView 的子项一旦 focusable，**selection 就不跟随焦点**：
 *     - onItemSelected 不再触发 → 焦点移到 CCTV2，节目单还停在 CCTV1；
 *     - getSelectedItemPosition() 恒为 0 → 确定键播的永远是第 0 台；
 *     - 遥控按"下"也移不动 —— GridView 不会为还没布局的子项生成视图，
 *       焦点搜索找不到下面的格子，列表也不会滚动。
 *   现在改成 App 自己管选中项（mSel）：
 *     - 格子不设 focusable（GridView 自己持焦，方向键由我们拦截）；
 *     - 方向键 → 算目标下标 → setSelection/smoothScroll 滚动 → 手动画选中框；
 *     - 触摸 → 格子自己的 OnClickListener 直接播。
 *   高亮用 state_selected 画（sel_channel.xml）。
 */
public class LiveActivity extends Activity {

    private static final String TAG = "moontv-live";

    private LinearLayout mSources;
    private LinearLayout mSourceInfo;
    private GridView mGrid;
    private LinearLayout mEpgList;
    private TextView mStatus;
    private TextView mEpgTitle;
    private ProgressBar mLoading;
    private Button mRefresh;

    private final ArrayList<LunaLive.Source> mSourceList = new ArrayList<LunaLive.Source>();
    private final ArrayList<LunaLive.Channel> mChannels = new ArrayList<LunaLive.Channel>();
    /** 各直播源的测速/分辨率信息（与 mSourceList 同序）。 */
    private final ArrayList<SourceProbe.Info> mSrcInfo = new ArrayList<SourceProbe.Info>();

    private ChannelAdapter mAdapter;
    private int mSourceIdx = -1;
    /** 当前选中的频道下标（App 自管，不依赖 GridView 的 selection）。 */
    private int mSel = -1;

    /** 当前节目单所属的频道（避免焦点来回移动时重复拉取）。 */
    private String mEpgFor = "";
    /** 当前节目单对应的频道名（显示用，比 tvgId 更友好）。 */
    private String mEpgName = "";
    /** EPG 请求序号：只有最新一次的结果才允许上屏（焦点移动很快时防串台）。 */
    private int mEpgSeq = 0;

    private final Handler mUi = new Handler(Looper.getMainLooper());
    private volatile boolean mAlive = true;

    /**
     * 直播相关的网络请求统一走这条单线程队列。
     *
     * 【为什么必须单线程】遥控在频道网格里快速移动时，每个频道都会触发一次 EPG 请求，
     * 一次快速扫过 50 个频道就会同时起 50 个线程（每个都在拉网络）→ 内存与连接数都会被拖垮。
     * 串行化后同一时刻只有 1 个请求在跑，其余排队；配合 mEpgSeq 序号，
     * 排队中的过期任务直接丢弃（不发起请求）。
     */
    private final java.util.concurrent.ExecutorService mNet =
            java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "live-net");
                    t.setDaemon(true);
                    return t;
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live);
        ThemeManager.applyDarkChrome(this);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        mSources = findViewById(R.id.live_sources);
        mSourceInfo = findViewById(R.id.live_source_info);
        mGrid = findViewById(R.id.live_grid);
        mEpgList = findViewById(R.id.live_epg_list);
        mStatus = findViewById(R.id.live_status);
        mEpgTitle = findViewById(R.id.live_epg_title);
        mLoading = findViewById(R.id.live_loading);

        Button back = findViewById(R.id.btn_back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        mRefresh = findViewById(R.id.btn_refresh);
        mRefresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refreshSources(); }
        });

        mAdapter = new ChannelAdapter();
        mGrid.setAdapter(mAdapter);
        // 触摸点频道直接看：格子自己处理点击（GridView 子项可聚焦时 item click 不可靠）
        mGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                playChannel(position);
            }
        });

        loadSources();
    }

    // ---------------------------------------------------------------- 数据

    /**
     * 加载直播源：缓存优先 + 后台刷新。
     *
     * 【老板需求】"默认优先读本地的源地址"——
     * 进页面时先读本地缓存（有就立即渲染），同时后台拉服务端最新数据；
     * 服务端返回后更新缓存并刷新 UI（用户无感，除非源列表变了）。
     * 点「刷新」按钮则强制拉服务端，不走缓存。
     */
    private void loadSources() {
        loadSources(false);
    }

    private void loadSources(boolean forceRefresh) {
        mLoading.setVisibility(View.VISIBLE);
        mStatus.setText(forceRefresh ? "正在刷新直播源…" : "正在加载直播源…");

        // ★ 缓存优先：本地有缓存就先用，后台再刷新
        if (!forceRefresh && Store.hasLiveSourcesCache(this)) {
            final ArrayList<LunaLive.Source> cached = Store.getLiveSources(this);
            if (!cached.isEmpty()) {
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!mAlive) return;
                        applySources(cached, false);
                    }
                });
                // 后台静默刷新（不显示 loading，不打断用户）
                mNet.execute(new Runnable() {
                    @Override
                    public void run() {
                        ArrayList<LunaLive.Source> fresh = fetchSourcesFromServer();
                        if (fresh != null && !fresh.isEmpty()) {
                            Store.saveLiveSources(LiveActivity.this, fresh);
                            mUi.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (!mAlive) return;
                                    applySources(fresh, true);
                                }
                            });
                        }
                    }
                });
                return;
            }
        }

        // 无缓存或强制刷新：直接走网络
        mNet.execute(new Runnable() {
            @Override
            public void run() {
                ArrayList<LunaLive.Source> list = fetchSourcesFromServer();
                final ArrayList<LunaLive.Source> fl = list;
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!mAlive) return;
                        mLoading.setVisibility(View.GONE);
                        if (fl == null) {
                            mStatus.setText("直播源刷新失败（已保留本地缓存）");
                            Diag.e(TAG, "sources failed", null);
                            return;
                        }
                        if (fl.isEmpty()) {
                            mStatus.setText("该站点未配置直播源（后台 LiveConfig 为空）");
                            Diag.i(TAG, "no live source configured");
                            return;
                        }
                        applySources(fl, true);
                    }
                });
            }
        });
    }

    /** 点击刷新按钮：强制从服务端拉最新直播源。 */
    private void refreshSources() {
        toast("正在刷新直播源…");
        loadSources(true);
    }

    /** 从服务端拉直播源（失败返回 null）。 */
    private ArrayList<LunaLive.Source> fetchSourcesFromServer() {
        try {
            return LunaLive.sources();
        } catch (Throwable t) {
            Diag.e(TAG, "sources failed: " + t.getMessage(), null);
            return null;
        }
    }

    /**
     * 把直播源列表应用到 UI（构建源条 / 源信息行 / 选中第一个源 / 探测速度）。
     *
     * @param sources  直播源列表
     * @param fromNet true=来自服务端（顺便存缓存），false=来自本地缓存
     */
    private void applySources(ArrayList<LunaLive.Source> sources, boolean fromNet) {
        mLoading.setVisibility(View.GONE);
        if (sources == null || sources.isEmpty()) {
            mStatus.setText("该站点未配置直播源（后台 LiveConfig 为空）");
            Diag.i(TAG, "no live source configured");
            return;
        }
        if (fromNet) {
            Store.saveLiveSources(this, sources);
        }
        mSourceList.clear();
        mSourceList.addAll(sources);
        mSrcInfo.clear();
        for (int i = 0; i < mSourceList.size(); i++) mSrcInfo.add(new SourceProbe.Info());
        mStatus.setText("共 " + mSourceList.size() + " 个直播源"
                + (fromNet ? "（已刷新）" : "（本地缓存）"));
        buildSourceBar();
        buildSourceInfoRow();
        // 自动选中第一个源并加载频道：
        // 否则遥控进页面时没有任何焦点落点（源按钮没焦点、频道网格还是空的 → 死路）
        selectSource(0);
        probeSources();
    }

    /** 直播源切换条。 */
    private void buildSourceBar() {
        mSources.removeAllViews();
        for (int i = 0; i < mSourceList.size(); i++) {
            final LunaLive.Source s = mSourceList.get(i);
            final int idx = i;
            Button b = new Button(this);
            b.setText(s.name == null || s.name.isEmpty() ? s.key : s.name);
            b.setTextColor(getResources().getColor(R.color.moontv_text));
            b.setBackgroundResource(R.drawable.sel_filter_chip);
            b.setFocusable(true);
            b.setFocusableInTouchMode(false);
            b.setAllCaps(false);
            b.setTextSize(14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = 12;
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectSource(idx); }
            });
            mSources.addView(b);
        }
    }

    /**
     * 源信息行：每个可切换的源直接显示「名称 / 速度 / 分辨率」，点一下就切。
     *
     * 【老板需求】"换源下面直接把能切换的源名称、速度、片源分辨率都显示出来，可以直接切"。
     */
    private void buildSourceInfoRow() {
        if (mSourceInfo == null) return;
        mSourceInfo.removeAllViews();
        for (int i = 0; i < mSourceList.size(); i++) {
            final LunaLive.Source s = mSourceList.get(i);
            final int idx = i;
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundResource(R.drawable.sel_channel);
            box.setFocusable(true);
            box.setFocusableInTouchMode(false);
            box.setClickable(true);
            box.setPadding(12, 6, 12, 6);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = 10;
            box.setLayoutParams(lp);

            TextView name = new TextView(this);
            name.setText(s.name == null || s.name.isEmpty() ? s.key : s.name);
            name.setTextColor(getResources().getColor(R.color.moontv_text));
            name.setTextSize(14);
            box.addView(name);

            TextView info = new TextView(this);
            info.setText("测速中…");
            info.setTextColor(getResources().getColor(R.color.moontv_text_dim));
            info.setTextSize(12);
            box.addView(info);

            box.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectSource(idx); }
            });
            mSourceInfo.addView(box);
        }
        updateSourceInfo();
    }

    /** 刷新源信息行上的速度/分辨率文案。 */
    private void updateSourceInfo() {
        if (mSourceInfo == null) return;
        for (int i = 0; i < mSourceInfo.getChildCount(); i++) {
            View box = mSourceInfo.getChildAt(i);
            if (!(box instanceof LinearLayout)) continue;
            LinearLayout l = (LinearLayout) box;
            if (l.getChildCount() < 2) continue;
            View iv = l.getChildAt(1);
            if (!(iv instanceof TextView)) continue;
            SourceProbe.Info pi = i < mSrcInfo.size() ? mSrcInfo.get(i) : null;
            ((TextView) iv).setText(SourceProbe.describe(pi));
        }
    }

    /** 后台逐个探测直播源：速度 + 分辨率。 */
    private void probeSources() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < mSourceList.size(); i++) {
                    if (!mAlive) return;
                    LunaLive.Source s = mSourceList.get(i);
                    SourceProbe.Info info = SourceProbe.probe(s.key, s.url);
                    if (i < mSrcInfo.size()) mSrcInfo.set(i, info);
                    mUi.post(new Runnable() {
                        @Override public void run() { updateSourceInfo(); }
                    });
                }
                // 当前源的分辨率再用"当前频道"补测一次（频道列表型源自身测不到分辨率）
                probeCurrentChannel();
            }
        }, "src-probe").start();
    }

    /** 用当前频道的 m3u8 补测分辨率（频道列表型源自身测不到分辨率）。 */
    private void probeCurrentChannel() {
        if (mSourceIdx < 0 || mSourceIdx >= mSourceList.size()) return;
        if (mChannels.isEmpty()) return;
        LunaLive.Channel c = mChannels.get(mChannels.size() - 1);
        if (c == null) return;
        LunaLive.Source s = mSourceList.get(mSourceIdx);
        if (s == null) return;
        final SourceProbe.Info info = SourceProbe.probe(s.key, c.url);
        if (info.resolution == null || info.resolution.isEmpty()) return;
        if (mSourceIdx >= mSrcInfo.size()) return;
        mSrcInfo.get(mSourceIdx).resolution = info.resolution;
        mUi.post(new Runnable() {
            @Override public void run() { updateSourceInfo(); }
        });
    }

    private void selectSource(int idx) {
        if (idx < 0 || idx >= mSourceList.size()) return;
        if (idx == mSourceIdx && !mChannels.isEmpty()) return;
        mSourceIdx = idx;
        final LunaLive.Source s = mSourceList.get(idx);
        mChannels.clear();
        mSel = -1;
        mAdapter.notifyDataSetChanged();
        mEpgFor = "";
        mEpgName = "";
        mEpgSeq++;                 // 作废在途的 EPG 请求
        mEpgList.removeAllViews();
        mEpgTitle.setText("");
        mLoading.setVisibility(View.VISIBLE);
        mStatus.setText("正在加载频道：" + (s.name == null ? s.key : s.name));
        Diag.i(TAG, "select source " + s.key + " url=" + s.url + " ua=" + s.ua);
        mNet.execute(new Runnable() {
            @Override
            public void run() {
                ArrayList<LunaLive.Channel> list = null;
                String err = null;
                try {
                    list = LunaLive.channels(s.key);
                } catch (Throwable t) {
                    err = t.getMessage();
                }
                final ArrayList<LunaLive.Channel> fl = list;
                final String fe = err;
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!mAlive) return;
                        mLoading.setVisibility(View.GONE);
                        if (fe != null) {
                            mStatus.setText("频道加载失败：" + fe);
                            Diag.e(TAG, "channels failed: " + fe, null);
                            return;
                        }
                        if (fl == null || fl.isEmpty()) {
                            mStatus.setText("该直播源没有频道");
                            return;
                        }
                        mChannels.clear();
                        mChannels.addAll(fl);
                        mAdapter.notifyDataSetChanged();
                        mStatus.setText((s.name == null ? s.key : s.name) + " · " + mChannels.size() + " 个频道");
                        Diag.i(TAG, "channels loaded: " + mChannels.size() + " from " + s.key);
                        // 选中第一个频道，并把焦点交给网格（遥控按上下就能换台）
                        setSel(0);
                        mGrid.setSelection(0);
                        mGrid.requestFocus();
                        probeCurrentChannel();
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- 选中与滚动

    /**
     * 切换选中频道（App 自管选中，GridView 只负责滚动）。
     */
    private void setSel(int next) {
        if (next < 0 || next >= mChannels.size()) return;
        mSel = next;
        // 让 GridView 把目标行滚到可见区
        try {
            mGrid.setSelection(next);
        } catch (Throwable ignore) {
        }
        paintSel();
        // 布局完成后再补一次：setSelection 不一定一次滚到位
        mGrid.post(new Runnable() {
            @Override
            public void run() {
                int first = mGrid.getFirstVisiblePosition();
                int last = mGrid.getLastVisiblePosition();
                if (mSel < first || mSel > last) {
                    try {
                        mGrid.smoothScrollToPosition(mSel);
                    } catch (Throwable ignore) {
                    }
                }
                paintSel();
            }
        });
        onFocusChannel(next);
    }

    /** 画选中框（按 state_selected，滚动前后各画一次）。 */
    private void paintSel() {
        try {
            int first = mGrid.getFirstVisiblePosition();
            for (int i = 0; i < mGrid.getChildCount(); i++) {
                View c = mGrid.getChildAt(i);
                if (c == null) continue;
                c.setSelected(first + i == mSel);
            }
        } catch (Throwable ignore) {
        }
    }

    /** 方向键移动选中：左右 ±1。 */
    private boolean moveSel(int dir) {
        if (mChannels.isEmpty() || mSel < 0) return false;
        int next = mSel + dir;
        if (next < 0 || next >= mChannels.size()) return false;
        setSel(next);
        return true;
    }

    // ---------------------------------------------------------------- 节目单

    /**
     * 选中某个频道：拉它的节目单。
     */
    private void onFocusChannel(int pos) {
        if (pos < 0 || pos >= mChannels.size()) return;
        if (mSourceIdx < 0 || mSourceIdx >= mSourceList.size()) return;
        LunaLive.Channel c = mChannels.get(pos);
        if (c == null) return;
        String tvg = c.tvgId == null ? "" : c.tvgId;
        String name = c.name == null ? "" : c.name;
        // 有些源没写 tvg-id，退回用频道名去查 EPG
        String query = tvg.isEmpty() ? name : tvg;
        if (query.isEmpty()) return;
        // 去重键带上频道名：不同频道可能共用同一个 tvg-id
        String dedupe = name + "|" + query;
        if (dedupe.equals(mEpgFor)) return;
        mEpgFor = dedupe;
        mEpgName = name;
        mEpgTitle.setText("节目单：" + (name.isEmpty() ? query : name) + " 加载中…");
        final int seq = ++mEpgSeq;
        final LunaLive.Source s = mSourceList.get(mSourceIdx);
        final String key = s == null ? "" : s.key;
        final String q = query;
        mNet.execute(new Runnable() {
            @Override
            public void run() {
                // 排队期间焦点又移走了 → 这次请求不必发出去
                if (seq != mEpgSeq) return;
                ArrayList<LunaLive.Program> list = null;
                try {
                    list = LunaLive.epg(key, q);
                } catch (Throwable t) {
                    Diag.e(TAG, "epg failed: " + t.getMessage(), null);
                }
                final ArrayList<LunaLive.Program> fl = list;
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!mAlive || seq != mEpgSeq) return;
                        showEpg(q, fl);
                    }
                });
            }
        });
    }

    private void showEpg(String query, ArrayList<LunaLive.Program> list) {
        mEpgList.removeAllViews();
        String label = mEpgName == null || mEpgName.isEmpty() ? query : mEpgName;
        if (list == null || list.isEmpty()) {
            mEpgTitle.setText("节目单：" + label + "（暂无）");
            return;
        }
        mEpgTitle.setText("节目单：" + label);
        for (int i = 0; i < list.size(); i++) {
            LunaLive.Program p = list.get(i);
            TextView tv = new TextView(this);
            tv.setText(epgTime(p.start) + "  " + (p.title == null ? "" : p.title));
            tv.setTextColor(getResources().getColor(R.color.moontv_text));
            tv.setTextSize(13);
            tv.setPadding(12, 8, 12, 8);
            tv.setBackgroundResource(R.drawable.sel_episode);
            // 节目单只是信息展示，不参与焦点竞争（否则遥控往下会掉进节目单里出不来）
            tv.setFocusable(false);
            tv.setFocusableInTouchMode(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = 10;
            tv.setLayoutParams(lp);
            mEpgList.addView(tv);
        }
    }

    /** XMLTV 时间 "20251026153000 +0800" -> "10-26 15:30"。 */
    private static String epgTime(String s) {
        if (null == s || s.length() < 12) return "";
        try {
            String mo = s.substring(4, 6);
            String d = s.substring(6, 8);
            String h = s.substring(8, 10);
            String mi = s.substring(10, 12);
            return mo + "-" + d + " " + h + ":" + mi;
        } catch (Throwable t) {
            return s;
        }
    }

    // ---------------------------------------------------------------- 播放

    private void playChannel(int pos) {
        if (pos < 0 || pos >= mChannels.size()) return;
        if (mSourceIdx < 0 || mSourceIdx >= mSourceList.size()) return;
        LunaLive.Channel c = mChannels.get(pos);
        LunaLive.Source s = mSourceList.get(mSourceIdx);
        if (c == null || s == null) return;
        if (c.url == null || c.url.isEmpty()) {
            toast("该频道没有播放地址");
            return;
        }
        Diag.i(TAG, "play channel " + c.name + " src=" + s.key + " url=" + c.url);
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_LIVE, true);
        i.putExtra(PlayerActivity.EXTRA_LIVE_URL, c.url);
        i.putExtra(PlayerActivity.EXTRA_LIVE_SOURCE, s.key);
        i.putExtra(PlayerActivity.EXTRA_LIVE_SOURCE_NAME, s.name);
        i.putExtra(PlayerActivity.EXTRA_LIVE_SOURCE_UA, s.ua);
        i.putExtra(PlayerActivity.EXTRA_LIVE_SOURCE_BASE, s.url);
        i.putExtra(PlayerActivity.EXTRA_LIVE_CHANNEL, c.name);
        i.putExtra(PlayerActivity.EXTRA_TITLE, c.name);
        i.putExtra(PlayerActivity.EXTRA_SOURCE, "live:" + s.key);
        i.putExtra(PlayerActivity.EXTRA_ID, c.id);
        startActivity(i);
    }

    // ---------------------------------------------------------------- 遥控

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int k = event.getKeyCode();
            View cf = getCurrentFocus();
            boolean gridHasFocus = mGrid.hasFocus()
                    || (cf != null && UiKit.isDescendantOf(cf, mGrid));
            if (gridHasFocus) {
                if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                    if (mSel >= 0) {
                        playChannel(mSel);
                        return true;
                    }
                } else if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (moveSel(-1)) return true;
                } else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (moveSel(1)) return true;
                } else if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int cols = mGrid.getNumColumns();
                    if (cols <= 0) cols = 1;
                    int dir = (k == KeyEvent.KEYCODE_DPAD_DOWN) ? 1 : -1;
                    int next = mSel + dir * cols;
                    if (next >= 0 && next < mChannels.size()) {
                        setSel(next);
                        return true;
                    }
                    // 已经在最上/最下 → 把焦点交回框架（往上回到源条）
                    return false;
                }
            }
            if (k == KeyEvent.KEYCODE_BACK) {
                finish();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void toast(String s) {
        try {
            android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignore) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mAlive = false;
        mUi.removeCallbacksAndMessages(null);
        mNet.shutdownNow();          // 掐掉排队中的直播网络请求，避免泄漏
        mSourceList.clear();
        mChannels.clear();
        mSrcInfo.clear();
    }

    // ---------------------------------------------------------------- 适配器

    private class ChannelAdapter extends BaseAdapter {

        @Override public int getCount() { return mChannels.size(); }
        @Override public Object getItem(int pos) { return mChannels.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(LiveActivity.this)
                        .inflate(R.layout.item_live_channel, parent, false);
            }
            LunaLive.Channel c = mChannels.get(position);
            TextView name = v.findViewById(R.id.ch_name);
            ImageView logo = v.findViewById(R.id.ch_logo);
            if (c == null) return v;
            name.setText(c.name == null ? "" : c.name);
            if (c.logo != null && !c.logo.isEmpty() && mSourceIdx >= 0 && mSourceIdx < mSourceList.size()) {
                logo.setVisibility(View.VISIBLE);
                // 台标用小图采样：一张 20KB，128 个台标全放得进内存缓存，来回翻不再下载
                ImageLoader.loadLogo(logo, LunaLive.logoUrl(c.logo, mSourceList.get(mSourceIdx).key), LiveActivity.this);
            } else {
                logo.setVisibility(View.GONE);
            }
            // 选中态由 App 自己画（state_selected）
            v.setSelected(position == mSel);
            // 触摸点频道直接看
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    int pos = UiKit.positionIn(mGrid, view);
                    if (pos < 0) pos = mGrid.getPositionForView(view);
                    if (pos < 0 || pos >= mChannels.size()) return;
                    setSel(pos);
                    playChannel(pos);
                }
            });
            return v;
        }
    }
}
