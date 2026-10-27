package com.tvshell.moontv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * 原生首页（菜单式）。
 *
 * 【为什么要改成菜单】老板反馈首页同时铺「继续观看 + 我的收藏 + 豆瓣」多行海报，
 * 在 API 25 / 192MB 堆的电视上光位图就能把堆撑爆（实测 OOM 崩溃）。
 * 现在改成菜单：**一次只显示一类内容**，只刷这一个菜单的数据，
 * 别的菜单的数据完全不加载，内存占用天然降下来。
 *
 * 【分页】菜单内是海报网格，一页只渲染一批（按屏幕宽度算列数 × 3 行），
 * 翻到下一页才去取/渲染下一批，取到就丢旧页位图（releaseImages）。
 *
 * 菜单项：
 *   继续观看 —— 本地播放进度（Store）+ 服务端 playrecords 同步
 *   我的收藏 —— 本地收藏（Store）+ 服务端 favorites 同步
 *   豆瓣推荐 —— /api/douban/recommends（服务端分页，可按类型/类别/年度筛）
 *   经典Top250 —— /api/douban?tag=top250（服务端分页）
 *
 * 豆瓣条目只有豆瓣 id，没有资源站 id，点击后按片名走 /api/search 找可播资源。
 */
public class MainActivity extends Activity {

    private static final String TAG = "moontv-home";

    // ---------------------------------------------------------- 菜单定义

    private static final int TAB_CONT = 0;    // 继续观看
    private static final int TAB_FAV = 1;     // 我的收藏
    private static final int TAB_DOUBAN = 2;  // 豆瓣推荐
    private static final int TAB_TOP = 3;     // 经典 Top250
    private static final int TAB_COUNT = 4;
    private static final String[] TAB_NAMES = {"继续观看", "我的收藏", "豆瓣推荐", "经典Top250"};

    // 卡片类型
    private static final int KIND_CONT = 0;
    private static final int KIND_FAV = 1;
    private static final int KIND_DOUBAN = 2;

    /** 一张卡片（只存元数据，位图由 ImageLoader 按需加载）。 */
    private static class Card {
        int kind;
        String poster;
        String title;
        String sub;
        Store.Progress p;
        Store.Favorite f;
        DoubanItem d;
    }

    /** 每个菜单的状态：分页缓存 + 是否还有下一页。 */
    private static class TabState {
        final ArrayList<ArrayList<Card>> pages = new ArrayList<ArrayList<Card>>();
        boolean hasNext;
        boolean loaded;
        boolean loading;
    }

    // ---------------------------------------------------------- 控件

    private EditText mBox;
    private LinearLayout mMenu;
    private View mFilterBar;
    private LinearLayout mFilterChips;
    private GridView mGrid;
    private TextView mEmpty;
    private ProgressBar mLoading;
    private Button mPrev;
    private Button mNext;
    private TextView mPageInfo;

    private final ArrayList<Button> mTabBtns = new ArrayList<Button>();
    private final ArrayList<TabState> mTabs = new ArrayList<TabState>();
    /** 当前页显示的卡片（只保留一页，别的页不占位图）。 */
    private final ArrayList<Card> mShown = new ArrayList<Card>();

    private int mTab = -1;
    private int mPage = 0;
    /** 每页多少张：列数 × 行数，运行时按屏幕宽度算。 */
    private int mPageSize = 12;
    /** 本次会话是否已同步过服务端收藏/进度。 */
    private boolean mCloudSynced;
    /** 页面代际：切菜单 +1，用来作废上一页的在途结果。 */
    private int mGen = 0;
    /** 防重复触发（网格 item click 与手点监听可能都触发）。 */
    private Card mLastCard;
    private long mLastClickAt;

    /** 豆瓣筛选条件。 */
    private DoubanFilter mFilter = new DoubanFilter("movie", "", "");

    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final OkKey mOk = new OkKey();

    // 类型选项。
    private static final String[] KIND_LABELS = {"电影", "剧集"};
    private static final String[] KIND_VALUES = {"movie", "tv"};

    /** 类别选项（豆瓣认得的标签；空 = 不限）。 */
    private static final String[] CAT_LABELS = {"不限", "热门", "豆瓣高分", "华语", "欧美", "韩国", "日本", "喜剧", "动作", "爱情", "科幻", "悬疑", "犯罪", "惊悚", "动画", "古装"};
    private static final String[] CAT_VALUES = {"", "热门", "豆瓣高分", "华语", "欧美", "韩国", "日本", "喜剧", "动作", "爱情", "科幻", "悬疑", "犯罪", "惊悚", "动画", "古装"};

    /** 年度选项（空 = 不限）。 */
    private static final String[] YEAR_LABELS = {"不限", "2026", "2025", "2024", "2023", "2022", "2021", "2020"};
    private static final String[] YEAR_VALUES = {"", "2026", "2025", "2024", "2023", "2022", "2021", "2020"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ThemeManager.applyDarkChrome(this);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        mBox = findViewById(R.id.search_box);
        mMenu = findViewById(R.id.home_menu);
        mFilterBar = findViewById(R.id.filter_bar);
        mGrid = findViewById(R.id.home_grid);
        mEmpty = findViewById(R.id.home_empty);
        mLoading = findViewById(R.id.home_loading);
        mPrev = findViewById(R.id.btn_prev);
        mNext = findViewById(R.id.btn_next);
        mPageInfo = findViewById(R.id.page_info);

        Button go = findViewById(R.id.btn_search);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSearch(mBox.getText().toString()); }
        });
        mBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    openSearch(mBox.getText().toString());
                    return true;
                }
                return false;
            }
        });
        Button fav = findViewById(R.id.btn_fav);
        fav.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openTab(TAB_FAV); }
        });
        Button live = findViewById(R.id.btn_live);
        live.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(MainActivity.this, LiveActivity.class));
                } catch (Throwable t) {
                    Log.w(TAG, "open live failed", t);
                    toast("直播页打开失败");
                }
            }
        });
        Button logout = findViewById(R.id.btn_logout);
        logout.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmLogout(); }
        });

        mPrev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { goPage(mPage - 1, false); }
        });
        mNext.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { goPage(mPage + 1, false); }
        });

        for (int i = 0; i < TAB_COUNT; i++) mTabs.add(new TabState());
        buildMenu();
        buildFilterBar();

        mGrid.setAdapter(new CardAdapter());
        mGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= mShown.size()) return;
                onCardClick(mShown.get(position));
            }
        });

        computePageSize();
        // 默认菜单：有播放记录看「继续观看」，没有就看「豆瓣推荐」（不然首屏是空的）
        openTab(Store.getContinueWatching(this).isEmpty() ? TAB_DOUBAN : TAB_CONT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiDumper.sCurrentActivity = this;
        // 从详情/播放返回时，本地菜单的数据可能变了（进度、收藏）
        if (mTab == TAB_CONT || mTab == TAB_FAV) {
            TabState st = mTabs.get(mTab);
            if (st.loaded && !st.loading) {
                loadLocal(mTab, false);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        UiDumper.sCurrentActivity = null;
    }

    /**
     * 遥控确定键 / 方向键。
     *
     * 【为什么不用 GridView.getSelectedItemPosition()】GridView 的子项是可聚焦控件时，
     * selection 不会跟着焦点走（onItemSelected 不触发），
     * 于是"选中项"永远停在第 0 项 —— 直播页就因此出现"焦点在 CCTV2、播放却是 CCTV1"。
     * 这里一律用 getPositionForView(焦点控件) 取真实位置。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int k = event.getKeyCode();
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                View f = getCurrentFocus();
                if (f == null) return super.dispatchKeyEvent(event);
                if (f instanceof EditText) return super.dispatchKeyEvent(event);   // 输入框交给平台
                if (UiKit.isDescendantOf(f, mGrid)) {
                    int pos = UiKit.positionIn(mGrid, f);
                    if (pos >= 0 && pos < mShown.size()) onCardClick(mShown.get(pos));
                    return true;
                }
                if (f.performClick()) return true;
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // 在本页最后一张上按右键 → 自动翻到下一页（"翻到了再显示"）
                View f = getCurrentFocus();
                if (f != null && UiKit.isDescendantOf(f, mGrid)) {
                    int pos = UiKit.positionIn(mGrid, f);
                    if (pos >= 0 && pos == mShown.size() - 1 && canNext()) {
                        goPage(mPage + 1, true);
                        return true;
                    }
                }
            }
        }
        if (mOk.handle(this, event)) return true;
        return super.dispatchKeyEvent(event);
    }

    // ---------------------------------------------------------- 菜单

    private void buildMenu() {
        mMenu.removeAllViews();
        mTabBtns.clear();
        for (int i = 0; i < TAB_COUNT; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(TAB_NAMES[i]);
            b.setTextColor(getResources().getColor(R.color.moontv_text));
            b.setBackgroundResource(R.drawable.sel_filter_chip);
            b.setFocusable(true);
            b.setFocusableInTouchMode(false);
            b.setAllCaps(false);
            b.setTextSize(15);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = 10;
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openTab(idx); }
            });
            mMenu.addView(b);
            mTabBtns.add(b);
        }
        updateMenuState();
    }

    private void updateMenuState() {
        for (int i = 0; i < mTabBtns.size(); i++) {
            mTabBtns.get(i).setSelected(i == mTab);
        }
    }

    /** 切换菜单：只加载这一个菜单的数据，别的菜单一概不碰。 */
    private void openTab(int idx) {
        if (idx < 0 || idx >= TAB_COUNT) return;
        mTab = idx;
        mPage = 0;
        mGen++;
        // 【老板要求】切换菜单后，上一个页面没下载完的也要停，专心刷当前页面。
        // 掐断在途 HTTP 请求 + 作废旧海报的下载任务（否则老设备上白下几十 MB）。
        MoontvApi.abortPending();
        ImageLoader.bumpGen();
        Diag.i(TAG, "switch tab=" + TAB_NAMES[idx] + " gen=" + mGen);
        updateMenuState();
        // 筛选条只在豆瓣推荐下有意义
        mFilterBar.setVisibility(idx == TAB_DOUBAN ? View.VISIBLE : View.GONE);

        TabState st = mTabs.get(idx);
        if (!st.loaded) {
            if (idx == TAB_CONT || idx == TAB_FAV) {
                loadLocal(idx, true);
            } else {
                // 先把上一个菜单的海报清掉（不然新数据没到之前会看到旧内容）
                clearShown();
                fetchRemote(idx, 0);
            }
            return;
        }
        render();
        focusFirstItem();
    }

    /** 清空当前网格（切换菜单时用，顺带释放位图）。 */
    private void clearShown() {
        try {
            UiKit.releaseImages(mGrid);
        } catch (Throwable ignore) {
        }
        mShown.clear();
        ((CardAdapter) mGrid.getAdapter()).notifyDataSetChanged();
        mEmpty.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------- 数据

    /**
     * 载入本地菜单（继续观看 / 我的收藏）。
     *
     * @param focus 是否把焦点落到第一张卡片上
     */
    private void loadLocal(int tab, boolean focus) {
        TabState st = mTabs.get(tab);
        st.loaded = true;
        st.loading = false;
        ArrayList<Card> all = new ArrayList<Card>();
        if (tab == TAB_CONT) {
            ArrayList<Store.Progress> list = Store.getContinueWatching(this);
            for (int i = 0; i < list.size(); i++) {
                Store.Progress p = list.get(i);
                Card c = new Card();
                c.kind = KIND_CONT;
                c.p = p;
                c.title = p.title;
                c.poster = p.poster;
                c.sub = "第" + (p.episodeIndex + 1) + "集 · " + fmtTime(p.position);
                all.add(c);
            }
        } else {
            ArrayList<Store.Favorite> list = Store.getFavorites(this);
            for (int i = 0; i < list.size(); i++) {
                Store.Favorite f = list.get(i);
                Card c = new Card();
                c.kind = KIND_FAV;
                c.f = f;
                c.title = f.title;
                c.poster = f.poster;
                c.sub = f.year;
                all.add(c);
            }
        }
        buildPages(st, all);
        render();
        if (focus) focusFirstItem();
        syncCloud(tab);
    }

    /** 把整份列表切成若干页（本地菜单，条数已知）。 */
    private void buildPages(TabState st, ArrayList<Card> all) {
        st.pages.clear();
        int size = Math.max(1, mPageSize);
        for (int i = 0; i < all.size(); i += size) {
            ArrayList<Card> pg = new ArrayList<Card>();
            for (int j = i; j < Math.min(i + size, all.size()); j++) pg.add(all.get(j));
            st.pages.add(pg);
        }
        if (st.pages.isEmpty()) st.pages.add(new ArrayList<Card>());
        st.hasNext = false;
    }

    /** 服务端分页拉取（豆瓣推荐 / Top250）。 */
    private void fetchRemote(final int tab, final int pageIdx) {
        fetchRemote(tab, pageIdx, true);
    }

    /**
     * @param focusAfter 拉完后是否把焦点放到网格第一张卡片上
     */
    private void fetchRemote(final int tab, final int pageIdx, final boolean focusAfter) {
        final TabState st = mTabs.get(tab);
        if (st.loading) return;
        st.loading = true;
        final int gen = mGen;
        mLoading.setVisibility(View.VISIBLE);
        mEmpty.setVisibility(View.GONE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                ArrayList<Card> cards = null;
                String err = null;
                try {
                    MoontvApi.DoubanPage p;
                    if (tab == TAB_DOUBAN) {
                        p = MoontvApi.doubanRecommends(mFilter.kind, mPageSize,
                                pageIdx * mPageSize, mFilter.tags());
                    } else {
                        p = MoontvApi.douban("movie", "top250", mPageSize, pageIdx * mPageSize);
                    }
                    if (p.code == 200 && p.list != null) {
                        cards = new ArrayList<Card>();
                        for (int i = 0; i < p.list.size(); i++) {
                            DoubanItem d = p.list.get(i);
                            Card c = new Card();
                            c.kind = KIND_DOUBAN;
                            c.d = d;
                            c.title = d.title;
                            c.poster = d.poster;
                            String sub = d.rate == null ? "" : d.rate;
                            if (d.year != null && !d.year.isEmpty()) {
                                sub = sub.isEmpty() ? d.year : (d.rate + " · " + d.year);
                            }
                            c.sub = sub;
                            cards.add(c);
                        }
                    } else {
                        err = "code=" + p.code + " " + p.message;
                    }
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                    Diag.e(TAG, "remote page failed tab=" + tab + " err=" + err, null);
                }
                final ArrayList<Card> fc = cards;
                final String fe = err;
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        st.loading = false;
                        mLoading.setVisibility(View.GONE);
                        // 页面已切走 → 这次结果作废（老板要求"专心刷当前页面"）
                        if (gen != mGen || mTab != tab) {
                            Diag.i(TAG, "drop stale page tab=" + tab + " gen=" + gen + " now=" + mGen);
                            return;
                        }
                        if (fe != null) {
                            mEmpty.setText("加载失败：" + fe);
                            mEmpty.setVisibility(View.VISIBLE);
                            return;
                        }
                        // 补齐中间的空页（例如用户直接跳页）
                        while (st.pages.size() < pageIdx) st.pages.add(new ArrayList<Card>());
                        if (st.pages.size() == pageIdx) st.pages.add(fc);
                        else st.pages.set(pageIdx, fc);
                        st.hasNext = fc.size() >= mPageSize;
                        st.loaded = true;
                        if (mTab == tab) {
                            mPage = pageIdx;
                            render();
                            if (focusAfter) focusFirstItem();
                        }
                    }
                });
            }
        }, "home-page").start();
    }

    /** 服务端同步（redis 收藏 / 播放记录），只在本菜单打开时做一次。 */
    private void syncCloud(final int tab) {
        if (mCloudSynced) return;
        mCloudSynced = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (tab == TAB_CONT) {
                    try {
                        final ArrayList<LunaStore.Rec> recs = LunaStore.playRecords();
                        mUi.post(new Runnable() {
                            @Override public void run() { applyCloudRecords(recs); }
                        });
                    } catch (Throwable t) {
                        Diag.e(TAG, "cloud playrecords failed: " + t.getMessage(), null);
                    }
                } else {
                    try {
                        final ArrayList<LunaStore.Fav> favs = LunaStore.favorites();
                        mUi.post(new Runnable() {
                            @Override public void run() { applyCloudFavorites(favs); }
                        });
                    } catch (Throwable t) {
                        Diag.e(TAG, "cloud favorites failed: " + t.getMessage(), null);
                    }
                }
            }
        }, "cloud").start();
    }

    private void applyCloudRecords(ArrayList<LunaStore.Rec> recs) {
        if (recs == null || recs.isEmpty()) return;
        for (int i = 0; i < recs.size(); i++) {
            LunaStore.Rec r = recs.get(i);
            Store.Progress p = new Store.Progress();
            p.source = r.source;
            p.id = r.id;
            p.title = r.title;
            p.poster = r.cover;
            p.episodeIndex = Math.max(0, r.index - 1);
            p.position = r.playTime;
            p.duration = r.totalTime;
            p.ts = r.saveTime;
            Store.savePlayProgress(this, p);
        }
        Diag.i(TAG, "cloud records=" + recs.size());
        if (mTab == TAB_CONT) loadLocal(TAB_CONT, false);
    }

    private void applyCloudFavorites(ArrayList<LunaStore.Fav> favs) {
        if (favs == null || favs.isEmpty()) return;
        for (int i = 0; i < favs.size(); i++) {
            LunaStore.Fav f = favs.get(i);
            if (f.source == null || f.id == null) continue;
            if (Store.isFavorite(this, f.id, f.source)) continue;
            Store.Favorite lf = new Store.Favorite();
            lf.id = f.id;
            lf.source = f.source;
            lf.title = f.title;
            lf.poster = f.cover;
            lf.year = f.year;
            Store.addFavorite(this, lf);
        }
        Diag.i(TAG, "cloud favorites=" + favs.size());
        if (mTab == TAB_FAV) loadLocal(TAB_FAV, false);
    }

    // ---------------------------------------------------------- 渲染 / 分页

    /** 每页多少张：按屏幕宽度算列数，固定 3 行。 */
    private void computePageSize() {
        try {
            float density = getResources().getDisplayMetrics().density;
            int wpx = getResources().getDisplayMetrics().widthPixels;
            int cell = (int) (130 * density) + (int) (8 * density);
            int cols = Math.max(3, wpx / Math.max(1, cell));
            mPageSize = cols * 3;
        } catch (Throwable t) {
            mPageSize = 12;
        }
        Diag.i(TAG, "pageSize=" + mPageSize);
    }

    private boolean canNext() {
        if (mTab < 0) return false;
        TabState st = mTabs.get(mTab);
        if (mTab == TAB_CONT || mTab == TAB_FAV) return mPage + 1 < st.pages.size();
        return st.hasNext;
    }

    /** 翻到第 idx 页。 */
    private void goPage(int idx, boolean focus) {
        if (mTab < 0 || idx < 0) return;
        TabState st = mTabs.get(mTab);
        boolean local = (mTab == TAB_CONT || mTab == TAB_FAV);
        if (local) {
            if (idx >= st.pages.size()) return;
            mPage = idx;
            render();
            if (focus) focusFirstItem();
            return;
        }
        // 远程：已缓存的页直接切，没缓存的现拉
        if (idx < st.pages.size()) {
            mPage = idx;
            render();
            if (focus) focusFirstItem();
            return;
        }
        if (!st.hasNext) return;
        fetchRemote(mTab, idx, focus);
    }

    /** 渲染当前页：释放旧位图 → 换数据 → 刷新页码条。 */
    private void render() {
        if (mTab < 0) return;
        TabState st = mTabs.get(mTab);
        if (mPage >= st.pages.size()) mPage = Math.max(0, st.pages.size() - 1);
        ArrayList<Card> pg = new ArrayList<Card>();
        if (mPage < st.pages.size()) pg = st.pages.get(mPage);

        // 【内存】换页前先把上一页 ImageView 上的 Bitmap 摘掉，
        // 否则 removeAllViews 之后 ART 迟迟不回收，低内存设备连续翻页就会 OOM。
        try {
            UiKit.releaseImages(mGrid);
        } catch (Throwable ignore) {
        }
        mShown.clear();
        mShown.addAll(pg);
        ((CardAdapter) mGrid.getAdapter()).notifyDataSetChanged();

        if (mShown.isEmpty()) {
            mEmpty.setText(emptyText());
            mEmpty.setVisibility(View.VISIBLE);
        } else {
            mEmpty.setVisibility(View.GONE);
        }
        updatePageBar();
    }

    private String emptyText() {
        if (mTab == TAB_CONT) return "还没有播放记录，去搜索看看";
        if (mTab == TAB_FAV) return "还没有收藏，进详情页点收藏";
        return "暂无数据";
    }

    private void updatePageBar() {
        TabState st = mTabs.get(mTab);
        boolean local = (mTab == TAB_CONT || mTab == TAB_FAV);
        if (local) {
            mPageInfo.setText("第 " + (mPage + 1) + " / " + st.pages.size() + " 页");
        } else {
            mPageInfo.setText("第 " + (mPage + 1) + " 页" + (st.hasNext ? " · 翻页看更多" : ""));
        }
        setBtnState(mPrev, mPage > 0);
        setBtnState(mNext, canNext());
    }

    /** 不可用的按钮变淡（背景 drawable 没有 disabled 态，只能靠透明度）。 */
    private void setBtnState(Button b, boolean enabled) {
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : 0.4f);
    }

    /** 焦点落到当前页第一张卡片。 */
    private void focusFirstItem() {
        mGrid.post(new Runnable() {
            @Override
            public void run() {
                View c = null;
                for (int i = 0; i < mGrid.getChildCount(); i++) {
                    View v = mGrid.getChildAt(i);
                    if (v != null) { c = v; break; }
                }
                if (c != null) c.requestFocus();
            }
        });
    }

    // ---------------------------------------------------------- 卡片点击

    private void onCardClick(Card c) {
        if (c == null) return;
        long now = System.currentTimeMillis();
        // 网格的 item click 与手点监听可能都触发，做一次去重
        if (c == mLastCard && now - mLastClickAt < 500) return;
        mLastCard = c;
        mLastClickAt = now;

        if (c.kind == KIND_CONT) {
            resumePlay(c.p);
        } else if (c.kind == KIND_FAV) {
            Store.Favorite f = c.f;
            Intent i = new Intent(this, DetailActivity.class);
            i.putExtra(DetailActivity.EXTRA_ID, f.id);
            i.putExtra(DetailActivity.EXTRA_SOURCE, f.source);
            i.putExtra(DetailActivity.EXTRA_TITLE, f.title);
            i.putExtra(DetailActivity.EXTRA_POSTER, f.poster);
            startActivity(i);
        } else {
            // 豆瓣条目无资源站 id → 按片名搜索可播资源
            Intent i = new Intent(this, SearchActivity.class);
            i.putExtra(SearchActivity.EXTRA_QUERY, c.title);
            startActivity(i);
        }
    }

    /** 继续观看：后台取详情 → 直接进播放器并续播。 */
    private void resumePlay(final Store.Progress p) {
        if (p == null) return;
        toast("正在打开…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                Detail d = null;
                try {
                    d = MoontvApi.detail(p.id, p.source);
                } catch (Throwable t) {
                    Diag.e(TAG, "resume detail failed: " + t.getMessage(), null);
                }
                final Detail fd = d;
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fd == null || fd.episodes.isEmpty()) {
                            toast("无法获取剧集信息");
                            return;
                        }
                        int idx = p.episodeIndex;
                        if (idx < 0 || idx >= fd.episodes.size()) idx = 0;
                        Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                        i.putExtra(PlayerActivity.EXTRA_ID, p.id);
                        i.putExtra(PlayerActivity.EXTRA_SOURCE, p.source);
                        i.putExtra(PlayerActivity.EXTRA_TITLE, fd.title);
                        i.putExtra(PlayerActivity.EXTRA_POSTER, p.poster);
                        i.putExtra(PlayerActivity.EXTRA_EPISODES, new ArrayList<Episode>(fd.episodes));
                        i.putExtra(PlayerActivity.EXTRA_INDEX, idx);
                        i.putExtra(PlayerActivity.EXTRA_START_POS, p.position);
                        startActivity(i);
                    }
                });
            }
        }).start();
    }

    private void openSearch(String q) {
        String s = q == null ? "" : q.trim();
        if (s.isEmpty()) {
            toast("请输入搜索关键词");
            return;
        }
        Intent i = new Intent(this, SearchActivity.class);
        i.putExtra(SearchActivity.EXTRA_QUERY, s);
        startActivity(i);
    }

    // ---------------------------------------------------------- 豆瓣筛选条

    /**
     * 构建筛选条（三个 chip：类型 / 类别 / 年度）。
     *
     * TV 遥控：chip 可聚焦，按确定键弹出选择列表（AlertDialog 单选），
     * 选完立即重新拉取豆瓣页。不用 Spinner（遥控上要额外按一下展开，麻烦）。
     */
    private void buildFilterBar() {
        if (mFilterBar == null) return;
        mFilterChips = mFilterBar.findViewById(R.id.filter_chips);
        if (mFilterChips == null) return;
        mFilterChips.removeAllViews();

        addChip("类型", currentLabel(KIND_LABELS, KIND_VALUES, mFilter.kind), new View.OnClickListener() {
            @Override public void onClick(View v) { pickKind(); }
        });
        addChip("类别", currentLabel(CAT_LABELS, CAT_VALUES, mFilter.category), new View.OnClickListener() {
            @Override public void onClick(View v) { pickCategory(); }
        });
        addChip("年度", currentLabel(YEAR_LABELS, YEAR_VALUES, mFilter.year), new View.OnClickListener() {
            @Override public void onClick(View v) { pickYear(); }
        });
    }

    private void addChip(String tag, String value, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(tag + "：" + value);
        b.setTextColor(getResources().getColor(R.color.moontv_text));
        b.setBackgroundResource(R.drawable.sel_filter_chip);
        b.setFocusable(true);
        b.setFocusableInTouchMode(false);
        b.setAllCaps(false);
        b.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = 10;
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        mFilterChips.addView(b);
    }

    /** 当前值对应的展示文案。 */
    private static String currentLabel(String[] labels, String[] values, String cur) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(cur)) return labels[i];
        }
        return labels[0];
    }

    private void pickKind() {
        new AlertDialog.Builder(this)
                .setTitle("选择类型")
                .setItems(KIND_LABELS, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        mFilter.kind = KIND_VALUES[w];
                        applyFilter();
                    }
                })
                .show();
    }

    private void pickCategory() {
        new AlertDialog.Builder(this)
                .setTitle("选择类别")
                .setItems(CAT_LABELS, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        mFilter.category = CAT_VALUES[w];
                        applyFilter();
                    }
                })
                .show();
    }

    private void pickYear() {
        new AlertDialog.Builder(this)
                .setTitle("选择年度")
                .setItems(YEAR_LABELS, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        mFilter.year = YEAR_VALUES[w];
                        applyFilter();
                    }
                })
                .show();
    }

    /** 筛选条件变了：重建 chip 文案 + 重新拉豆瓣页（清掉旧缓存）。 */
    private void applyFilter() {
        buildFilterBar();
        Diag.i(TAG, "filter changed: " + mFilter.kind + " tags=" + mFilter.tags());
        TabState st = mTabs.get(TAB_DOUBAN);
        st.pages.clear();
        st.hasNext = false;
        st.loaded = false;
        st.loading = false;
        if (mTab == TAB_DOUBAN) {
            mPage = 0;
            fetchRemote(TAB_DOUBAN, 0);
        }
    }

    // ---------------------------------------------------------- 退出登录

    /**
     * 退出当前登录账号。
     *
     * 流程：二次确认 → 后端 POST /api/logout（让服务端清掉 redis 里的会话）
     *      → 清本地 auth cookie（AuthHelper + java.net CookieManager）
     *      → 清本地账号数据（播放进度 / 收藏属于当前账号，换人登录不该看到）
     *      → 回登录页。
     */
    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("确定退出当前账号吗？\n本机的播放进度与收藏会一并清除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        doLogout();
                    }
                })
                .show();
    }

    private void doLogout() {
        toast("正在退出…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    MoontvApi.logout();
                } catch (Throwable t) {
                    Diag.e(TAG, "logout failed: " + t.getMessage(), null);
                }
                Store.clearAccountData(MainActivity.this);
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        Intent i = new Intent(MainActivity.this, LoginActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                        finish();
                        toast("已退出登录");
                    }
                });
            }
        }, "logout").start();
    }

    private static String fmtTime(long sec) {
        long s = sec < 0 ? 0 : sec;
        return String.format("%02d:%02d", (s % 3600) / 60, s % 60);
    }

    private void toast(String s) {
        try {
            android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignore) {
        }
    }

    // ---------------------------------------------------------- 适配器

    private class CardAdapter extends BaseAdapter {

        @Override public int getCount() { return mShown.size(); }
        @Override public Object getItem(int i) { return mShown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_poster, parent, false);
            }
            if (position >= mShown.size()) return v;
            final Card c = mShown.get(position);
            ImageView iv = v.findViewById(R.id.poster);
            TextView t = v.findViewById(R.id.title);
            TextView s = v.findViewById(R.id.sub);
            t.setText(c.title == null ? "" : c.title);
            s.setText(c.sub == null ? "" : c.sub);
            s.setVisibility(c.sub == null || c.sub.isEmpty() ? View.GONE : View.VISIBLE);
            if (c.poster != null && !c.poster.isEmpty()) {
                ImageLoader.load(iv, c.poster, MainActivity.this);
            } else {
                iv.setImageBitmap(null);
            }
            v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean hasFocus) {
                    UiKit.applyFocus(view, hasFocus);
                }
            });
            // 卡片自己处理点击：手点与遥控确定键都走这里（确定键由 dispatchKeyEvent 转调）
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    int pos = UiKit.positionIn(mGrid, view);
                    if (pos < 0 || pos >= mShown.size()) return;
                    onCardClick(mShown.get(pos));
                }
            });
            return v;
        }
    }
}
