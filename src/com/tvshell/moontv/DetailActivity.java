package com.tvshell.moontv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * 原生详情页 + 选集。
 *
 * 数据来源有两条，互为兜底：
 *  1) /api/search 的结果本身带 episodes（可直接播放的 m3u8 数组）——这是主路径，
 *     不依赖 /api/detail，因此上游某些源 403/503 也不影响播放；
 *  2) /api/detail 补充简介/海报/集名，失败不影响播放。
 */
public class DetailActivity extends Activity {

    public static final String EXTRA_ID = "id";
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_POSTER = "poster";
    public static final String EXTRA_YEAR = "year";
    public static final String EXTRA_DESC = "desc";
    public static final String EXTRA_SOURCE_NAME = "source_name";
    /** 搜索结果自带的可播地址（ArrayList&lt;String&gt;）。 */
    public static final String EXTRA_EPISODES = "episodes";
    /** 搜索结果自带的集名（ArrayList&lt;String&gt;）。 */
    public static final String EXTRA_EP_TITLES = "ep_titles";
    /** 同片名的其它候选源（ArrayList&lt;SearchResult&gt;）。 */
    public static final String EXTRA_ALT_SOURCES = "alt_sources";

    private static final String TAG = "moontv-detail";

    private TextView mTitle;
    private TextView mMeta;
    private TextView mDesc;
    private TextView mStatus;
    private ImageView mPoster;
    private GridLayout mEpGrid;
    private Button mFav;
    private Button mPlay;
    private Button mSwitch;

    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final OkKey mOk = new OkKey();
    private String mId;
    private String mSource;
    private String mTitleStr;
    private String mPosterUrl;
    private String mDescStr;
    private Detail mDetail;

    /** 当前可播列表（来自 search 结果，优先）。 */
    private ArrayList<Episode> mEpisodes = new ArrayList<Episode>();
    /** 同片名的候选源（换源用）。 */
    private ArrayList<SearchResult> mAlts = new ArrayList<SearchResult>();
    private int mAltIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        ThemeManager.applyDarkChrome(this);

        mTitle = findViewById(R.id.title);
        mMeta = findViewById(R.id.meta);
        mDesc = findViewById(R.id.desc);
        mStatus = findViewById(R.id.status);
        mPoster = findViewById(R.id.poster);
        mEpGrid = findViewById(R.id.episodes);
        mFav = findViewById(R.id.btn_fav);
        mPlay = findViewById(R.id.btn_play);
        mSwitch = findViewById(R.id.btn_switch);

        mId = getIntent().getStringExtra(EXTRA_ID);
        mSource = getIntent().getStringExtra(EXTRA_SOURCE);
        mTitleStr = getIntent().getStringExtra(EXTRA_TITLE);
        mPosterUrl = getIntent().getStringExtra(EXTRA_POSTER);
        mDescStr = getIntent().getStringExtra(EXTRA_DESC);

        // 搜索结果自带的可播地址（主路径，不依赖 detail）
        ArrayList<String> urls = (ArrayList<String>) getIntent().getSerializableExtra(EXTRA_EPISODES);
        ArrayList<String> titles = (ArrayList<String>) getIntent().getSerializableExtra(EXTRA_EP_TITLES);
        if (urls != null) {
            for (int i = 0; i < urls.size(); i++) {
                Episode ep = new Episode();
                ep.url = urls.get(i);
                ep.episode = (titles != null && i < titles.size() && titles.get(i) != null
                        && !titles.get(i).isEmpty()) ? titles.get(i) : String.valueOf(i + 1);
                mEpisodes.add(ep);
            }
        }
        ArrayList<SearchResult> alts =
                (ArrayList<SearchResult>) getIntent().getSerializableExtra(EXTRA_ALT_SOURCES);
        if (alts != null) mAlts = alts;

        if (mTitleStr != null) mTitle.setText(mTitleStr);
        if (mPosterUrl != null && !mPosterUrl.isEmpty()) ImageLoader.load(mPoster, mPosterUrl, this);
        if (mDescStr != null && !mDescStr.isEmpty()) mDesc.setText(mDescStr);

        mFav.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleFav(); }
        });
        mPlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { playEpisode(0); }
        });
        mSwitch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSourceDialog(); }
        });

        // 【内存】X5 内核改为按需加载：不再在 Application 启动时预热
        // （低内存设备上启动就载入内核 dex 会直接吃穿 192MB 堆导致首页 OOM 崩溃）。
        // 这里在详情页后台悄悄预热一次——用户在详情页挑选/读简介通常有几秒，
        // 点播放时内核已就绪，可秒开。内核加载完不创建任何 WebView，不占额外堆。
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 用 Application context：Activity 可能已销毁，避免泄漏
                    X5KernelManager.get().init(getApplicationContext(),
                            new X5KernelManager.Callback() {
                                @Override
                                public void onResult(int status, String kernelVersion) {
                                    Diag.i(TAG, "kernel warmup status=" + status
                                            + " ver=" + kernelVersion);
                                }
                            });
                } catch (Throwable t) {
                    Diag.e(TAG, "kernel warmup failed: " + t.getMessage(), null);
                }
            }
        }, "x5-warmup").start();

        if (mId == null || mSource == null) {
            mStatus.setText("缺少影片参数");
            return;
        }

        // 先用搜索结果自带的剧集渲染（立即可播）
        if (!mEpisodes.isEmpty()) {
            renderEpisodes();
            mStatus.setText("共 " + mEpisodes.size() + " 集 · 按确认键播放");
        }
        // 线路信息行：名称 / 速度 / 分辨率，点一下直接切
        buildSourceList();
        // 初始焦点落在「播放」按钮上，遥控直接按确定就能播
        mPlay.post(new Runnable() {
            @Override public void run() { mPlay.requestFocus(); }
        });

        // 再拉 detail 补简介/海报/集名（失败不影响播放）
        loadDetail();
    }

    /** 遥控确定键：平台默认不触发卡片/选集点击，这里主动 performClick。 */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (mOk.handle(this, event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private void loadDetail() {
        final String id = mId;
        final String source = mSource;
        new Thread(new Runnable() {
            @Override
            public void run() {
                Detail d = null;
                String err = null;
                try {
                    d = MoontvApi.detail(id, source);
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                final Detail fd = d;
                final String fe = err;
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fe != null) {
                            Diag.e(TAG, "detail failed: " + fe, null);
                            // 已有 search 自带剧集 → 忽略 detail 失败
                            if (mEpisodes.isEmpty()) {
                                mStatus.setText("该资源详情加载失败：" + fe + "\n可点「换源」选择其它线路");
                            }
                            return;
                        }
                        mDetail = fd;
                        applyDetail();
                    }
                });
            }
        }).start();
    }

    private void applyDetail() {
        if (mDetail == null) return;
        if (mDetail.title != null && !mDetail.title.isEmpty()) mTitle.setText(mDetail.title);
        if (mDetail.desc != null && !mDetail.desc.isEmpty()) mDesc.setText(mDetail.desc);
        if (mDetail.poster != null && !mDetail.poster.isEmpty()) {
            ImageLoader.load(mPoster, mDetail.poster, this);
            mPosterUrl = mDetail.poster;
        }
        // detail 的集数更多时以 detail 为准（集数相同则保留 search 的地址）
        if (mDetail.episodes.size() > mEpisodes.size()) {
            mEpisodes = mDetail.episodes;
            renderEpisodes();
        }
        mMeta.setText(buildMeta());
        mStatus.setText(mEpisodes.isEmpty() ? "该资源暂无可播放剧集" : "共 " + mEpisodes.size() + " 集 · 按确认键播放");
        Diag.i(TAG, "detail ok episodes=" + mDetail.episodes.size());
    }

    private String buildMeta() {
        StringBuilder sb = new StringBuilder();
        String sn = (mDetail != null && mDetail.sourceName != null && !mDetail.sourceName.isEmpty())
                ? mDetail.sourceName : mSource;
        sb.append("来源：").append(sn);
        String yr = mDetail != null ? mDetail.year : null;
        if (yr == null || yr.isEmpty()) yr = getIntent().getStringExtra(EXTRA_YEAR);
        if (yr != null && !yr.isEmpty() && !"unknown".equals(yr)) sb.append(" · ").append(yr);
        sb.append(" · 共 ").append(mEpisodes.size()).append(" 集");
        return sb.toString();
    }

    /** 渲染选集宫格。 */
    private void renderEpisodes() {
        if (mEpisodes == null || mEpisodes.isEmpty()) return;
        mEpGrid.removeAllViews();
        LayoutInflater li = LayoutInflater.from(this);
        for (int i = 0; i < mEpisodes.size(); i++) {
            final int idx = i;
            Episode ep = mEpisodes.get(i);
            TextView tv = (TextView) li.inflate(R.layout.item_episode, mEpGrid, false);
            String label = (ep.episode == null || ep.episode.isEmpty()) ? String.valueOf(i + 1) : ep.episode;
            tv.setText(label);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.height = 44;
            lp.setMargins(4, 4, 4, 4);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { playEpisode(idx); }
            });
            mEpGrid.addView(tv);
        }
        mMeta.setText(buildMeta());
    }

    /** 换源：在同片名的候选源之间轮换（search 结果里同标题的多线路）。 */
    private void switchSource() {
        if (mAlts == null || mAlts.isEmpty()) {
            toast("没有其它线路");
            return;
        }
        mAltIndex = (mAltIndex + 1) % mAlts.size();
        applySource(mAltIndex);
    }

    /** 换源面板：列出所有可切换线路（名称 / 速度 / 分辨率），点一下直接切。 */
    private void showSourceDialog() {
        if (mAlts == null || mAlts.isEmpty()) {
            toast("没有其它线路");
            return;
        }
        final ArrayList<SearchResult> alts = new ArrayList<SearchResult>(mAlts);
        toast("正在测速…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ArrayList<String> labels = new ArrayList<String>();
                final int[] resolutions = new int[alts.size()];
                for (int i = 0; i < alts.size(); i++) {
                    SearchResult s = alts.get(i);
                    String nm = (s.sourceName == null || s.sourceName.isEmpty()) ? s.source : s.sourceName;
                    String url = (s.episodes != null && !s.episodes.isEmpty()) ? s.episodes.get(0) : null;
                    SourceProbe.Info info = SourceProbe.probeUrl(url);
                    String res = (info.ok && !info.resolution.isEmpty()) ? info.resolution : "—";
                    resolutions[i] = 0; // placeholder
                    labels.add(nm + " · " + res + " · " + info.ms + "ms · " + s.episodes.size() + "集");
                }
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        new android.app.AlertDialog.Builder(DetailActivity.this)
                                .setTitle("换源")
                                .setItems(labels.toArray(new String[0]),
                                        new android.content.DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(android.content.DialogInterface d, int which) {
                                                if (which < 0 || which >= alts.size()) return;
                                                mAltIndex = which;
                                                applySource(which);
                                            }
                                        })
                                .show();
                    }
                });
            }
        }, "src-probe").start();
    }

    /**
     * 线路信息行：把能切换的源（名称 / 速度 / 分辨率）直接列出来，点一下就切。
     */
    private void buildSourceList() {
        LinearLayout list = findViewById(R.id.source_list);
        if (list == null) return;
        list.removeAllViews();
        if (mAlts == null || mAlts.isEmpty()) {
            View v = findViewById(R.id.source_scroll);
            if (v != null) v.setVisibility(View.GONE);
            return;
        }
        View v = findViewById(R.id.source_scroll);
        if (v != null) v.setVisibility(View.VISIBLE);
        for (int i = 0; i < mAlts.size(); i++) {
            final int idx = i;
            SearchResult s = mAlts.get(i);
            String nm = (s.sourceName == null || s.sourceName.isEmpty()) ? s.source : s.sourceName;
            View chip = UiKit.buildSourceChipWeb(this, nm, "—", "测速中…", s.episodes.size() + "集", new View.OnClickListener() {
                @Override public void onClick(View view) {
                    mAltIndex = idx;
                    applySource(idx);
                }
            });
            list.addView(chip);
        }
        // 后台测速，测完刷新文案
        final ArrayList<SearchResult> alts = new ArrayList<SearchResult>(mAlts);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ArrayList<String> resolutions = new ArrayList<String>();
                final ArrayList<String> speeds = new ArrayList<String>();
                for (int i = 0; i < alts.size(); i++) {
                    SearchResult s = alts.get(i);
                    String url = (s.episodes != null && !s.episodes.isEmpty()) ? s.episodes.get(0) : null;
                    SourceProbe.Info info = SourceProbe.probeUrl(url);
                    String res = (info.ok && !info.resolution.isEmpty()) ? info.resolution : "—";
                    String spd = info.ok ? (info.ms + "ms") : "—";
                    resolutions.add(res);
                    speeds.add(spd);
                }
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        LinearLayout l = (LinearLayout) findViewById(R.id.source_list);
                        if (l == null) return;
                        for (int i = 0; i < l.getChildCount() && i < resolutions.size(); i++) {
                            View chip = l.getChildAt(i);
                            if (!(chip instanceof LinearLayout)) continue;
                            LinearLayout box = (LinearLayout) chip;
                            if (box.getChildCount() < 2) continue;
                            // 上排是 top LinearLayout（有分隔线），下排是 info TextView
                            LinearLayout top = (LinearLayout) box.getChildAt(0);
                            if (!(top instanceof LinearLayout)) continue;
                            // 分辨率 TextView 是 top 的第 2 个子项（源名 | 分隔线 | 分辨率）
                            if (top.getChildCount() >= 3) {
                                View tRes = top.getChildAt(2);
                                if (tRes instanceof TextView) ((TextView) tRes).setText(resolutions.get(i));
                            }
                            // 速度 TextView 是 box 的第 1 个子项（info row）
                            View tInfo = box.getChildAt(1);
                            if (tInfo instanceof TextView) ((TextView) tInfo).setText(speeds.get(i) + " · " + alts.get(i).episodes.size() + "集");
                        }
                    }
                });
            }
        }, "src-list-probe").start();
    }

    /** 切到第 idx 条线路。 */
    private void applySource(int idx) {
        if (mAlts == null || idx < 0 || idx >= mAlts.size()) return;
        SearchResult s = mAlts.get(idx);
        mId = s.id;
        mSource = s.source;
        mEpisodes = new ArrayList<Episode>();
        for (int i = 0; i < s.episodes.size(); i++) {
            Episode ep = new Episode();
            ep.url = s.episodes.get(i);
            ep.episode = (i < s.episodeTitles.size() && s.episodeTitles.get(i) != null
                    && !s.episodeTitles.get(i).isEmpty()) ? s.episodeTitles.get(i) : String.valueOf(i + 1);
            mEpisodes.add(ep);
        }
        if (s.title != null) mTitle.setText(s.title);
        if (s.poster != null && !s.poster.isEmpty()) {
            mPosterUrl = s.poster;
            ImageLoader.load(mPoster, s.poster, this);
        }
        renderEpisodes();
        mStatus.setText("已切换到 " + (s.sourceName == null ? s.source : s.sourceName)
                + " · 共 " + mEpisodes.size() + " 集");
        Diag.i(TAG, "switch source -> " + s.source + "/" + s.id + " eps=" + mEpisodes.size());
    }

    /**
     * 收藏切换：本地立即生效（UI 响应快），同时写服务端 redis（与网页版同步）。
     * 服务端失败不阻塞 UI，本地仍有记录。
     */
    private void toggleFav() {
        if (mId == null || mSource == null) return;
        final boolean now;
        if (Store.isFavorite(this, mId, mSource)) {
            Store.removeFavorite(this, mId, mSource);
            now = false;
            // 同步删除服务端收藏
            final String sid = mId, ssrc = mSource;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        LunaStore.removeFavorite(ssrc, sid);
                        Diag.i(TAG, "cloud fav removed " + ssrc + "/" + sid);
                    } catch (Throwable t) {
                        Diag.e(TAG, "cloud fav remove failed: " + t.getMessage(), null);
                    }
                }
            }, "fav-del").start();
        } else {
            Store.Favorite f = new Store.Favorite();
            f.id = mId;
            f.source = mSource;
            f.title = mDetail != null ? mDetail.title : mTitleStr;
            f.poster = mPosterUrl;
            f.year = mDetail != null ? mDetail.year : "";
            Store.addFavorite(this, f);
            now = true;
            // 同步写入服务端收藏
            final String sid = mId, ssrc = mSource;
            final String title = f.title == null ? "" : f.title;
            final String cover = mPosterUrl == null ? "" : mPosterUrl;
            final String year = f.year == null ? "" : f.year;
            final String sname = mDetail != null && mDetail.sourceName != null ? mDetail.sourceName : ssrc;
            final int total = mEpisodes.size();
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        LunaStore.addFavorite(ssrc, sid, title, cover, year, sname, total, title);
                        Diag.i(TAG, "cloud fav added " + ssrc + "/" + sid);
                    } catch (Throwable t) {
                        Diag.e(TAG, "cloud fav add failed: " + t.getMessage(), null);
                    }
                }
            }, "fav-add").start();
        }
        mFav.setText(now ? "取消收藏" : "收藏");
    }

    private void playEpisode(int index) {
        if (mEpisodes == null || mEpisodes.isEmpty()) {
            toast("暂无可播放剧集，可点「换源」");
            return;
        }
        if (index < 0 || index >= mEpisodes.size()) {
            toast("没有更多剧集");
            return;
        }
        Episode ep = mEpisodes.get(index);
        if (ep.url == null || ep.url.isEmpty()) {
            toast("该集没有播放地址");
            return;
        }
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_ID, mId);
        i.putExtra(PlayerActivity.EXTRA_SOURCE, mSource);
        i.putExtra(PlayerActivity.EXTRA_TITLE, mTitleStr);
        i.putExtra(PlayerActivity.EXTRA_POSTER, mPosterUrl);
        i.putExtra(PlayerActivity.EXTRA_EPISODES, new ArrayList<Episode>(mEpisodes));
        i.putExtra(PlayerActivity.EXTRA_INDEX, index);
        // 把可切换线路带进播放器（播放界面底部菜单的「换源」要用）
        if (mAlts != null && !mAlts.isEmpty()) {
            i.putExtra(PlayerActivity.EXTRA_ALT_SOURCES, new ArrayList<SearchResult>(mAlts));
        }
        startActivity(i);
    }

    private void toast(String s) {
        try {
            android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignore) {
        }
    }
}
