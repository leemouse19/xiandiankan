package com.tvshell.moontv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * 原生搜索页（FR：接口直出，不走网页识别）。
 *
 * GET /api/search?q=xxx → results[]（id/title/type_name/poster/year/source）
 * 点击结果 → DetailActivity（带 id + source）。
 */
public class SearchActivity extends Activity {

    public static final String EXTRA_QUERY = "query";

    private static final String TAG = "moontv-search";

    private EditText mBox;
    private GridView mGrid;
    private TextView mEmpty;
    private final Handler mUi = new Handler(Looper.getMainLooper());
    private ArrayList<SearchResult> mResults = new ArrayList<>();
    private boolean mSearching = false;
    private final OkKey mOk = new OkKey();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        ThemeManager.applyDarkChrome(this);
        // 启动时不要自动弹输入法，避免遮住结果网格
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        mBox = findViewById(R.id.search_box);
        mGrid = findViewById(R.id.grid);
        mEmpty = findViewById(R.id.empty);

        Button back = findViewById(R.id.btn_back);
        Button go = findViewById(R.id.btn_search);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startSearch(mBox.getText().toString()); }
        });
        mBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    startSearch(mBox.getText().toString());
                    return true;
                }
                return false;
            }
        });

        mGrid.setAdapter(new ResultAdapter());
        mGrid.setOnItemClickListener(new GridView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= mResults.size()) return;
                openDetail(mResults.get(position));
            }
        });

        String q = getIntent().getStringExtra(EXTRA_QUERY);
        if (!TextUtils.isEmpty(q)) {
            mBox.setText(q);
            startSearch(q);
        } else {
            loadHistory();
        }
    }

    /** 载入服务端搜索历史（LunaTV redis，与网页版共享），点条目直接搜。 */
    private void loadHistory() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ArrayList<String> hist;
                try {
                    hist = LunaStore.searchHistory();
                } catch (Throwable t) {
                    Diag.e(TAG, "searchhistory failed: " + t.getMessage(), null);
                    return;
                }
                mUi.post(new Runnable() {
                    @Override public void run() { showHistory(hist); }
                });
            }
        }, "history").start();
    }

    private void showHistory(ArrayList<String> hist) {
        if (hist == null || hist.isEmpty()) {
            mEmpty.setText("输入关键词开始搜索");
            mEmpty.setVisibility(View.VISIBLE);
            return;
        }
        mResults = new ArrayList<SearchResult>();
        for (String h : hist) {
            SearchResult r = new SearchResult();
            r.id = "hist:" + h;
            r.title = h;
            r.episodes = new ArrayList<String>();
            r.episodeTitles = new ArrayList<String>();
            mResults.add(r);
        }
        mEmpty.setVisibility(View.GONE);
        ((ResultAdapter) mGrid.getAdapter()).notifyDataSetChanged();
        mGrid.setOnItemClickListener(new GridView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= mResults.size()) return;
                SearchResult r = mResults.get(position);
                if (r.id != null && r.id.startsWith("hist:")) {
                    String kw = r.id.substring(5);
                    mBox.setText(kw);
                    startSearch(kw);
                } else {
                    openDetail(r);
                }
            }
        });
    }

    private void startSearch(String raw) {
        final String q = raw == null ? "" : raw.trim();
        if (q.isEmpty()) {
            toast("请输入搜索关键词");
            return;
        }
        if (mSearching) return;
        mSearching = true;
        mEmpty.setVisibility(View.GONE);
        mEmpty.setText("搜索中…");
        mEmpty.setVisibility(View.VISIBLE);
        Diag.i(TAG, "search q=" + q);

        // 搜索历史写入服务端（redis，与网页版共享）
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    LunaStore.addSearchHistory(q);
                } catch (Throwable t) {
                    Diag.e(TAG, "searchhistory add failed: " + t.getMessage(), null);
                }
            }
        }, "history-add").start();

        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                ArrayList<SearchResult> list = null;
                try {
                    list = MoontvApi.search(q);
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                final ArrayList<SearchResult> fl = list;
                final String fe = err;
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        mSearching = false;
                        if (fe != null) {
                            Diag.e(TAG, "search failed: " + fe, null);
                            mEmpty.setText("搜索失败：" + fe);
                            mEmpty.setVisibility(View.VISIBLE);
                            return;
                        }
                        mResults = fl == null ? new ArrayList<SearchResult>() : fl;
                        Diag.i(TAG, "search done count=" + mResults.size());
                        // 恢复「点结果进详情」的监听（历史模式曾覆盖它）
                        mGrid.setOnItemClickListener(new GridView.OnItemClickListener() {
                            @Override
                            public void onItemClick(android.widget.AdapterView<?> parent, View view,
                                                   int position, long id) {
                                if (position < 0 || position >= mResults.size()) return;
                                openDetail(mResults.get(position));
                            }
                        });
                        // 诊断：确认 search 结果是否自带可播地址（episodes）
                        int withEps = 0;
                        int maxEps = 0;
                        String firstSrc = "";
                        for (SearchResult sr : mResults) {
                            if (sr.hasEpisodes()) {
                                withEps++;
                                if (sr.episodes.size() > maxEps) maxEps = sr.episodes.size();
                            }
                            if (firstSrc.isEmpty()) firstSrc = sr.source + "/" + sr.sourceName;
                        }
                        Diag.i(TAG, "search eps: withEpisodes=" + withEps + "/" + mResults.size()
                                + " maxEpisodes=" + maxEps + " firstSource=" + firstSrc);
                        ((ResultAdapter) mGrid.getAdapter()).notifyDataSetChanged();
                        if (mResults.isEmpty()) {
                            mEmpty.setText("没有找到相关内容");
                            mEmpty.setVisibility(View.VISIBLE);
                        } else {
                            mEmpty.setVisibility(View.GONE);
                        }
                    }
                });
            }
        }).start();
    }

    private void openDetail(SearchResult r) {
        if (r == null) return;
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra(DetailActivity.EXTRA_ID, r.id);
        i.putExtra(DetailActivity.EXTRA_SOURCE, r.source);
        i.putExtra(DetailActivity.EXTRA_TITLE, r.title);
        i.putExtra(DetailActivity.EXTRA_POSTER, r.poster);
        i.putExtra(DetailActivity.EXTRA_YEAR, r.year);
        i.putExtra(DetailActivity.EXTRA_DESC, r.desc);
        i.putExtra(DetailActivity.EXTRA_SOURCE_NAME, r.sourceName);
        // 搜索结果自带可播地址 → 详情页立即可播，不必等 /api/detail（部分源 403）
        i.putExtra(DetailActivity.EXTRA_EPISODES, new ArrayList<String>(r.episodes));
        i.putExtra(DetailActivity.EXTRA_EP_TITLES, new ArrayList<String>(r.episodeTitles));
        // 同片名的其它线路，供详情页「换源」使用
        i.putExtra(DetailActivity.EXTRA_ALT_SOURCES, collectAlternatives(r));
        startActivity(i);
    }

    /** 收集同标题（忽略空格/年份）的其它线路，优先带可播地址的排前面。 */
    private ArrayList<SearchResult> collectAlternatives(SearchResult cur) {
        ArrayList<SearchResult> out = new ArrayList<SearchResult>();
        if (cur == null) return out;
        String key = normTitle(cur.title);
        for (SearchResult s : mResults) {
            if (s == cur) continue;
            if (s == null || s.id == null) continue;
            if (!key.equals(normTitle(s.title))) continue;
            out.add(s);
        }
        // 有可播地址的排前面
        java.util.Collections.sort(out, new java.util.Comparator<SearchResult>() {
            @Override
            public int compare(SearchResult a, SearchResult b) {
                boolean ha = a.hasEpisodes();
                boolean hb = b.hasEpisodes();
                if (ha && !hb) return -1;
                if (!ha && hb) return 1;
                return 0;
            }
        });
        return out;
    }

    private static String normTitle(String t) {
        if (t == null) return "";
        return t.replaceAll("\\s+", "").replaceAll("[（(].*?[）)]", "");
    }

    /** 遥控确定键：平台默认不会触发 item click，这里主动 performClick。 */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mOk.handle(this, event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private void toast(String s) {
        try {
            android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignore) {
        }
    }

    private class ResultAdapter extends BaseAdapter {
        @Override public int getCount() { return mResults.size(); }
        @Override public Object getItem(int i) { return mResults.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Context ctx = parent.getContext();
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(ctx).inflate(R.layout.item_poster, parent, false);
            }
            SearchResult r = mResults.get(position);
            ImageView iv = v.findViewById(R.id.poster);
            TextView t = v.findViewById(R.id.title);
            TextView s = v.findViewById(R.id.sub);
            t.setText(r.title == null ? "" : r.title);
            boolean isHist = r.id != null && r.id.startsWith("hist:");
            String sub = r.year == null ? "" : r.year;
            if (r.typeName != null && !r.typeName.isEmpty()) {
                sub = sub.isEmpty() ? r.typeName : (r.typeName + " · " + sub);
            }
            // 有可播地址的排在前面，并标出集数，让用户一眼知道哪条线路能播
            if (isHist) {
                sub = "历史记录";
            } else if (r.hasEpisodes()) {
                sub = sub.isEmpty() ? (r.episodes.size() + "集可播")
                        : (sub + " · " + r.episodes.size() + "集可播");
            } else if (r.sourceName != null && !r.sourceName.isEmpty()) {
                sub = sub.isEmpty() ? r.sourceName : (sub + " · " + r.sourceName);
            }
            s.setText(sub);
            s.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
            if (!isHist && r.poster != null && !r.poster.isEmpty()) {
                ImageLoader.load(iv, r.poster, ctx);
            } else {
                iv.setImageBitmap(null);
            }
            v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean hasFocus) {
                    UiKit.applyFocus(view, hasFocus);
                }
            });
            // 卡片自己处理点击：手点与遥控确定键（performClick）走同一条路径
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    int pos = UiKit.positionIn(mGrid, view);
                    if (pos < 0 || pos >= mResults.size()) return;
                    SearchResult r = mResults.get(pos);
                    if (r.id != null && r.id.startsWith("hist:")) {
                        // 历史条目 → 重新搜索
                        String kw = r.id.substring(5);
                        mBox.setText(kw);
                        startSearch(kw);
                    } else {
                        openDetail(r);
                    }
                }
            });
            return v;
        }
    }
}
