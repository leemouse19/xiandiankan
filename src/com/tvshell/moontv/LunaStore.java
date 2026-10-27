package com.tvshell.moontv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * LunaTV 服务端用户数据（收藏 / 播放记录 / 搜索历史）。
 *
 * 【为什么需要】老板部署的是 MoontechLab 的 LunaTV（redis 存储），
 * 网页版收藏/进度都存在服务端redis 里。原生客户端若只用本地 SharedPreferences，
 * 就和网页版数据完全割裂——网页收藏的片在App 里看不到，App收藏的网页也看不到。
 * 所以这里对接 LunaTV 的服务端接口，实现多端同步；本地 Store 作为离线兜底。
 *
 * 接口契约（已核对上游源码 src/app/api/favorites|playrecords|searchhistory/route.ts）：
 *   GET    /api/favorites              -> { [key]: Favorite }，key = source + "+" + id
 *   GET    /api/favorites?key=source+id-> Favorite | null
 *   POST   /api/favorites   body {key, favorite}
 *   DELETE /api/favorites?key=source+id
 *   GET    /api/playrecords            -> { [key]: PlayRecord }
 *   POST   /api/playrecords body {key, record}
 *   DELETE /api/playrecords?key=source+id
 *   GET    /api/searchhistory          -> string[]
 *   POST   /api/searchhistory body {keyword} -> string[]（返回最新列表）
 *   DELETE /api/searchhistory?keyword=xxx
 *
 * Favorite 字段：source_name, total_episodes, title, year, cover, save_time, search_title
 * PlayRecord 字段：title, source_name, cover, year, index(第几集，从1开始), total_episodes,
 *                 play_time(秒), total_time(秒), save_time, search_title
 */
public class LunaStore {

    private static final String TAG = "moontv-luna";

    // ===================== 服务端收藏 =====================

    /** 服务端收藏条目。 */
    public static class Fav {
        public String key;        // source+id
        public String source;
        public String id;
        public String title;
        public String cover;
        public String year;
        public String sourceName;
        public int totalEpisodes;
        public long saveTime;
        public String searchTitle;
    }

    /** 拉取服务端全部收藏。失败抛异常（调用方决定是否回落本地）。 */
    public static ArrayList<Fav> favorites() throws Exception {
        String resp = MoontvApi.get(MoontvApi.BASE + "/api/favorites");
        JSONObject o = new JSONObject(resp);
        ArrayList<Fav> out = new ArrayList<Fav>();
        java.util.Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            JSONObject v = o.optJSONObject(k);
            if (v == null) continue;
            Fav f = new Fav();
            f.key = k;
            f.title = v.optString("title");
            f.cover = v.optString("cover");
            f.year = v.optString("year");
            f.sourceName = v.optString("source_name");
            f.totalEpisodes = v.optInt("total_episodes");
            f.saveTime = v.optLong("save_time");
            f.searchTitle = v.optString("search_title");
            // key 形如 source+id
            int p = k.indexOf('+');
            if (p > 0) {
                f.source = k.substring(0, p);
                f.id = k.substring(p + 1);
            } else {
                f.source = k;
            }
            out.add(f);
        }
        return out;
    }

    /** 保存收藏到服务端。 */
    public static void addFavorite(String source, String id, String title, String cover,
                                    String year, String sourceName, int totalEpisodes,
                                    String searchTitle) throws Exception {
        JSONObject fav = new JSONObject();
        fav.put("title", title == null ? "" : title);
        fav.put("cover", cover == null ? "" : cover);
        fav.put("year", year == null ? "" : year);
        fav.put("source_name", sourceName == null ? source : sourceName);
        fav.put("total_episodes", totalEpisodes);
        fav.put("save_time", System.currentTimeMillis());
        fav.put("search_title", searchTitle == null ? title : searchTitle);
        JSONObject body = new JSONObject();
        body.put("key", source + "+" + id);
        body.put("favorite", fav);
        MoontvApi.post(MoontvApi.BASE + "/api/favorites", body.toString(), "application/json");
    }

    /** 删除服务端收藏。
     *
     * 【坑】key 形如 source+id，服务端用 key.split('+') 拆源与 id。
     * 查询串里 '+' 会被 URLSearchParams 解成空格，必须整体编码成 %2B，
     * 否则服务端只拿到 "source id" 一段 → "Invalid key format" 400。
     */
    public static void removeFavorite(String source, String id) throws Exception {
        MoontvApi.delete(MoontvApi.BASE + "/api/favorites?key="
                + URLEnc(source + "+" + id));
    }

    // ===================== 服务端播放记录 =====================

    /** 服务端播放记录。 */
    public static class Rec {
        public String key;
        public String source;
        public String id;
        public String title;
        public String cover;
        public String year;
        public String sourceName;
        public int index;          // 第几集（服务端从 1 开始）
        public int totalEpisodes;
        public long playTime;      // 已播秒
        public long totalTime;     // 总时长秒
        public long saveTime;
        public String searchTitle;
    }

    /** 拉取服务端全部播放记录。 */
    public static ArrayList<Rec> playRecords() throws Exception {
        String resp = MoontvApi.get(MoontvApi.BASE + "/api/playrecords");
        JSONObject o = new JSONObject(resp);
        ArrayList<Rec> out = new ArrayList<Rec>();
        java.util.Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            JSONObject v = o.optJSONObject(k);
            if (v == null) continue;
            Rec r = new Rec();
            r.key = k;
            r.title = v.optString("title");
            r.cover = v.optString("cover");
            r.year = v.optString("year");
            r.sourceName = v.optString("source_name");
            r.index = v.optInt("index");
            r.totalEpisodes = v.optInt("total_episodes");
            r.playTime = v.optLong("play_time");
            r.totalTime = v.optLong("total_time");
            r.saveTime = v.optLong("save_time");
            r.searchTitle = v.optString("search_title");
            int p = k.indexOf('+');
            if (p > 0) {
                r.source = k.substring(0, p);
                r.id = k.substring(p + 1);
            } else {
                r.source = k;
            }
            out.add(r);
        }
        return out;
    }

    /** 保存播放记录到服务端。index 从 1 开始（服务端校验 record.index < 1 会拒绝）。 */
    public static void savePlayRecord(String source, String id, String title, String cover,
                                       String year, String sourceName, int index,
                                       int totalEpisodes, long playTime, long totalTime,
                                       String searchTitle) throws Exception {
        JSONObject rec = new JSONObject();
        rec.put("title", title == null ? "" : title);
        rec.put("cover", cover == null ? "" : cover);
        rec.put("year", year == null ? "" : year);
        rec.put("source_name", sourceName == null ? source : sourceName);
        rec.put("index", Math.max(1, index));
        rec.put("total_episodes", totalEpisodes);
        rec.put("play_time", playTime);
        rec.put("total_time", totalTime);
        rec.put("save_time", System.currentTimeMillis());
        rec.put("search_title", searchTitle == null ? title : searchTitle);
        JSONObject body = new JSONObject();
        body.put("key", source + "+" + id);
        body.put("record", rec);
        MoontvApi.post(MoontvApi.BASE + "/api/playrecords", body.toString(), "application/json");
    }

    /** 删除服务端播放记录（key 里的 '+' 必须整体编码，见 removeFavorite 的说明）。 */
    public static void removePlayRecord(String source, String id) throws Exception {
        MoontvApi.delete(MoontvApi.BASE + "/api/playrecords?key="
                + URLEnc(source + "+" + id));
    }

    // ===================== 搜索历史 =====================

    /** 拉取服务端搜索历史（最新在前，最多 20 条）。 */
    public static ArrayList<String> searchHistory() throws Exception {
        String resp = MoontvApi.get(MoontvApi.BASE + "/api/searchhistory");
        ArrayList<String> out = new ArrayList<String>();
        JSONArray arr = new JSONArray(resp);
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** 追加搜索历史（服务端去重并置顶）。 */
    public static void addSearchHistory(String keyword) throws Exception {
        if (keyword == null || keyword.trim().isEmpty()) return;
        JSONObject body = new JSONObject();
        body.put("keyword", keyword.trim());
        MoontvApi.post(MoontvApi.BASE + "/api/searchhistory", body.toString(), "application/json");
    }

    /** 删除一条搜索历史。 */
    public static void removeSearchHistory(String keyword) throws Exception {
        if (keyword == null || keyword.isEmpty()) return;
        MoontvApi.delete(MoontvApi.BASE + "/api/searchhistory?keyword=" + URLEnc(keyword));
    }

    private static String URLEnc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }
}
