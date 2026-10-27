package com.tvshell.moontv;

import java.io.Serializable;
import java.util.ArrayList;

/**
 * 搜索结果项（/api/search）。
 *
 * 真实契约（MoonTV 上游 mapItemToResult）：
 *   { id, title, poster, episodes: string[], episodes_titles: string[],
 *     source, source_name, class, year, desc, type_name, douban_id }
 *
 * 关键：episodes 是「可直接播放的 m3u8 URL 字符串数组」，不是对象数组。
 * 这一点让播放不必依赖 /api/detail（后者部分源会 403/503）。
 */
public class SearchResult implements Serializable {
    public String id;
    public String title;
    public String typeName;
    public String poster;
    public String year;
    public String source;
    public String sourceName;
    public String desc;
    /** 可播地址（m3u8/mp4），可能为空数组。 */
    public ArrayList<String> episodes = new ArrayList<String>();
    /** 每一集的标题（与 episodes 一一对应）。 */
    public ArrayList<String> episodeTitles = new ArrayList<String>();

    /** 是否自带可播地址。 */
    public boolean hasEpisodes() {
        return episodes != null && !episodes.isEmpty();
    }
}
