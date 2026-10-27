package com.tvshell.moontv;

/**
 * 豆瓣筛选条件（首页筛选条）。
 *
 * 【为什么这么设计】老板反馈"首页放出来的都是老片子"。
 * 原来首页固定拉 /api/douban?type=&tag=hot|top250 —— top250 本身就是 1990~2010 年的老片，
 * hot 也常年是老片，所以怎么刷新都是老内容。
 *
 * 换成 /api/douban/recommends（上游网页版豆瓣页真正用的接口）后可按年度/类型筛，
 * 实测（直连豆瓣 rexxar 底座）：
 *   - tags=2025 → 返回 2025 年的新片（哪吒2、疯狂动物城2…），total 500
 *   - tags=2024 / 2023 / 2026 同理，均返回对应年份
 *   - tags=华语 / 欧美 / 喜剧 / 古装 等类型标签同样生效（可与年份组合，如 "喜剧,2025"）
 *   - 但中文类目名（"热门""最新"）在豆瓣端 total=0，不能直接当标签用
 * 所以这里的 category 只放豆瓣认得的标签。
 *
 * 服务端 /api/douban/recommends 会把 category 参数塞进豆瓣的 tags，
 * 客户端直接传 "年份,类型" 这样的逗号组合即可。
 */
public class DoubanFilter {

    /** 类型：不限 / 电影 / 剧集。 */
    public String kind = "movie";

    /** 豆瓣标签：类型/地区（可空）。 */
    public String category = "";

    /** 年度（可空）。 */
    public String year = "";

    public DoubanFilter() {
    }

    public DoubanFilter(String kind, String category, String year) {
        this.kind = kind;
        this.category = category == null ? "" : category;
        this.year = year == null ? "" : year;
    }

    /**
     * 组装成豆瓣 tags 字符串（逗号分隔）。
     * 顺序：类型标签在前、年度在后（与豆瓣推荐页一致）。
     */
    public String tags() {
        StringBuilder sb = new StringBuilder();
        if (category != null && !category.isEmpty()) sb.append(category);
        if (year != null && !year.isEmpty()) {
            if (sb.length() > 0) sb.append(",");
            sb.append(year);
        }
        return sb.toString();
    }

    /** 当前条件对应的展示文案（行标题用）。 */
    public String label() {
        StringBuilder sb = new StringBuilder();
        if ("tv".equals(kind)) sb.append("剧集");
        else if ("movie".equals(kind)) sb.append("电影");
        else sb.append("全部");
        if (category != null && !category.isEmpty()) {
            sb.append(" · ").append(category);
        }
        if (year != null && !year.isEmpty()) {
            sb.append(" · ").append(year);
        }
        return sb.toString();
    }
}
