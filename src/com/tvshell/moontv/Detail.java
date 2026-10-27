package com.tvshell.moontv;

/** 详情（/api/detail）：影片元信息 + 剧集列表。
 *
 * 真实契约（MoonTV 上游 getDetailFromApi 返回 SearchResult）：
 *   { id, title, poster, desc, year, source, source_name,
 *     episodes: string[], episodes_titles: string[] }
 *
 * episodes 是「URL 字符串数组」，不是 [{episode,url}] 对象数组。
 */
public class Detail {
    public String id;
    public String title;
    public String poster;
    public String desc;
    public String year;
    public String sourceName;
    public java.util.ArrayList<Episode> episodes = new java.util.ArrayList<Episode>();
}
