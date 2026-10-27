package com.tvshell.moontv;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 原生 UI 小组件：横向海报行 / 单个海报卡片。
 *
 * 不用 RecyclerView（非 Gradle 构建无 AndroidX），改用 HorizontalScrollView + LinearLayout 手动 inflate。
 */
public final class UiKit {

    private UiKit() {}

    /** 往行容器里加一个横向海报行，返回该行的 items 容器（用于继续往里塞卡片）。 */
    public static LinearLayout addRow(Context ctx, LinearLayout rowsContainer, String rowTitle) {
        LayoutInflater li = LayoutInflater.from(ctx);
        View row = li.inflate(R.layout.item_row, rowsContainer, false);
        TextView tv = row.findViewById(R.id.row_title);
        tv.setText(rowTitle);
        rowsContainer.addView(row);
        LinearLayout items = row.findViewById(R.id.row_items);
        return items;
    }

    /**
     * 生成一个海报卡片。
     *
     * @param poster 海报图 URL（可空）
     * @param title  主标题
     * @param sub    副标题（年份/评分/来源，可空）
     * @return 卡片根 View（已加载海报）
     */
    public static View posterItem(Context ctx, LinearLayout rowItems, String poster, String title, String sub) {
        LayoutInflater li = LayoutInflater.from(ctx);
        View v = li.inflate(R.layout.item_poster, rowItems, false);
        // 关键：必须真正加进行容器，否则卡片只 inflate 出来却没显示
        rowItems.addView(v);
        ImageView iv = v.findViewById(R.id.poster);
        TextView t = v.findViewById(R.id.title);
        TextView s = v.findViewById(R.id.sub);
        t.setText(title == null ? "" : title);
        s.setText(sub == null ? "" : sub);
        s.setVisibility(sub == null || sub.isEmpty() ? View.GONE : View.VISIBLE);
        if (poster != null && !poster.isEmpty()) {
            ImageLoader.load(iv, poster, ctx);
        }
        // TV 遥控：焦点必须"看得见"——卡片外框 + 海报区同时加亮边框
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                applyFocus(view, hasFocus);
            }
        });
        return v;
    }

    /**
     * 释放一个 View 树里所有 ImageView 持有的 Bitmap 引用。
     *
     * 【内存】首页每次 refreshLocalRows() 都会 removeAllViews() 全量重建卡片。
     * removeAllViews 只是把 View 从容器摘下来，ImageView 的 drawable 仍持有 Bitmap，
     * 而 ART 的回收要等到 GC —— 在 API 25（Bitmap 像素在 Java 堆里）的低内存设备上，
     * 重建 2~3 次就足以把 192MB 堆撑爆（已实测 OOM 崩溃）。
     * 这里在移除前主动把 drawable 置空，让 Bitmap 立刻可回收。
     */
    public static void releaseImages(View root) {
        if (root == null) return;
        if (root instanceof ImageView) {
            try {
                ((ImageView) root).setImageDrawable(null);
            } catch (Throwable ignore) {
            }
            return;
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                releaseImages(g.getChildAt(i));
            }
        }
    }

    /**
     * 造一个「线路信息卡」——网页版风格：横向布局，左上源名、右上分辨率高亮，下方速度。
     *
     * 【老板需求】跟 Web 版换源一致：名称 | 分辨率（绿色高亮），下一行速度+集数。
     */
    public static LinearLayout buildSourceChipWeb(Context ctx, String name, String resolution,
                                                   String speed, String epCount,
                                                   View.OnClickListener l) {
        LinearLayout box = new LinearLayout(ctx);
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

        // 上排：源名 + 分隔线 + 分辨率
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView tName = new TextView(ctx);
        tName.setText(name == null ? "" : name);
        tName.setTextColor(ctx.getResources().getColor(R.color.moontv_text));
        tName.setTextSize(13);
        LinearLayout.LayoutParams lpName = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tName.setLayoutParams(lpName);
        top.addView(tName);

        // 分隔竖线
        View sep = new View(ctx);
        LinearLayout.LayoutParams lpSep = new LinearLayout.LayoutParams(2, 20);
        lpSep.leftMargin = 6;
        lpSep.rightMargin = 6;
        sep.setLayoutParams(lpSep);
        sep.setBackgroundColor(0x44FFFFFF);
        top.addView(sep);

        TextView tRes = new TextView(ctx);
        String resText = (resolution == null || resolution.isEmpty()) ? "—" : resolution;
        tRes.setText(resText);
        tRes.setTextColor(ctx.getResources().getColor(R.color.moontv_green));
        tRes.setTextSize(12);
        top.addView(tRes);

        box.addView(top);

        // 下排：速度 · 集数
        String infoText = speed;
        if (epCount != null && !epCount.isEmpty()) {
            infoText = infoText + " · " + epCount;
        }
        TextView tInfo = new TextView(ctx);
        tInfo.setText(infoText == null ? "测速中…" : infoText);
        tInfo.setTextColor(ctx.getResources().getColor(R.color.moontv_text_dim));
        tInfo.setTextSize(11);
        box.addView(tInfo);

        if (l != null) box.setOnClickListener(l);
        return box;
    }

    /**
     * 造一个「线路信息卡」：上面线路名，下面速度/分辨率。
     *
     * 【老板需求】"换源下面直接把能切换的源名称、速度、片源分辨率都显示出来，可以直接切"。
     */
    public static LinearLayout buildSourceChip(Context ctx, String name, String info,
                                                View.OnClickListener l) {
        LinearLayout box = new LinearLayout(ctx);
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

        TextView t1 = new TextView(ctx);
        t1.setText(name == null ? "" : name);
        t1.setTextColor(ctx.getResources().getColor(R.color.moontv_text));
        t1.setTextSize(14);
        box.addView(t1);

        TextView t2 = new TextView(ctx);
        t2.setText(info == null ? "测速中…" : info);
        t2.setTextColor(ctx.getResources().getColor(R.color.moontv_text_dim));
        t2.setTextSize(12);
        box.addView(t2);

        if (l != null) box.setOnClickListener(l);
        return box;
    }

    /**
     * 求 v 在列表（AdapterView）里的位置。
     *
     * 【为什么要自己爬】AdapterView.getPositionForView() 只认"列表的直接子项"，
     * 传更深层的控件（比如格子里的 TextView）会返回 -1。
     * 这里先沿 parent 链爬到列表的直接子项，再取位置。
     *
     * @return 位置，-1 表示不在列表里
     */
    public static int positionIn(android.widget.AdapterView<?> av, View v) {
        if (av == null || v == null) return -1;
        android.view.ViewParent p = v.getParent();
        while (p != null && p != av) {
            if (!(p instanceof View)) return -1;
            v = (View) p;
            p = v.getParent();
        }
        if (p != av) return -1;
        return av.getPositionForView(v);
    }

    /**
     * v 是否是 root 的后代（含 root 自己）。
     *
     * 【为什么需要】GridView 的子项可聚焦时，selection 不跟随焦点走，
     * getSelectedItemPosition() 永远是第 0 项 —— 直播页因此"焦点在 CCTV2、播放却是 CCTV1"。
     * 判断焦点到底在哪个格子里，只能靠焦点控件在视图树里的位置。
     */
    public static boolean isDescendantOf(View v, android.view.ViewGroup root) {
        if (v == null || root == null) return false;
        if (v == root) return true;
        android.view.ViewParent p = v.getParent();
        while (p != null) {
            if (p == root) return true;
            if (p instanceof View) p = ((View) p).getParent();
            else p = null;
        }
        return false;
    }

    /**
     * 统一的高亮表现（首页卡片 / 搜索网格共用）。
     *
     * 之前只给根布局换了背景色，描边画在 6dp padding 的外缘，被 118dp 海报几乎完全盖住，
     * 遥控下根本看不出焦点在哪。现在卡片外框与海报区同时描边+变色+放大。
     */
    public static void applyFocus(View root, boolean hasFocus) {
        if (root == null) return;
        root.setBackgroundResource(hasFocus ? R.drawable.card_focus : R.drawable.card_normal);
        ImageView poster = root.findViewById(R.id.poster);
        if (poster != null) {
            poster.setBackgroundResource(hasFocus ? R.drawable.poster_focus : R.drawable.poster_normal);
        }
        float s = hasFocus ? 1.08f : 1f;
        root.setScaleX(s);
        root.setScaleY(s);
    }
}
