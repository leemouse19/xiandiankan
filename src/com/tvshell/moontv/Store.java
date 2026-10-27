package com.tvshell.moontv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * 本地存储（取代原 H5 的 localStorage / 客户端 DB）：
 *  - 播放进度（按 source:id 记录集数、位置、时长、标题、海报）-> 继续观看
 *  - 收藏
 *  - 跳过片头尾配置（按 source:id）
 */
public class Store {

    private static final String PREFS = "moontv_store";
    private static final String K_PROGRESS = "progress";
    private static final String K_FAV = "favorites";
    private static final String K_SKIP = "skip";
    private static final String K_LIVE_SOURCES = "live_sources";

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String key(String source, String id) {
        return source + ":" + id;
    }

    /** 播放进度条目。 */
    public static class Progress {
        public String source;
        public String id;
        public String title;
        public String poster;
        public int episodeIndex;
        public long position;   // 秒
        public long duration;   // 秒
        public long ts;         // 最后更新时间
    }

    public static class Favorite {
        public String id;
        public String source;
        public String title;
        public String poster;
        public String year;
    }

    public static void savePlayProgress(Context ctx, Progress p) {
        try {
            SharedPreferences.Editor e = sp(ctx).edit();
            JSONArray arr = new JSONArray(sp(ctx).getString(K_PROGRESS, "[]"));
            JSONObject o = new JSONObject();
            o.put("source", p.source);
            o.put("id", p.id);
            o.put("title", p.title);
            o.put("poster", p.poster);
            o.put("episodeIndex", p.episodeIndex);
            o.put("position", p.position);
            o.put("duration", p.duration);
            o.put("ts", p.ts);
            String k = key(p.source, p.id);
            // 覆盖同 key
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);
                if (key(it.optString("source"), it.optString("id")).equals(k)) continue;
                out.put(it);
            }
            out.put(o);
            e.putString(K_PROGRESS, out.toString());
            e.apply();
        } catch (Exception ignore) {
        }
    }

    public static Progress getPlayProgress(Context ctx, String source, String id) {
        try {
            JSONArray arr = new JSONArray(sp(ctx).getString(K_PROGRESS, "[]"));
            String k = key(source, id);
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject it = arr.getJSONObject(i);
                if (key(it.optString("source"), it.optString("id")).equals(k)) {
                    Progress p = new Progress();
                    p.source = it.optString("source");
                    p.id = it.optString("id");
                    p.title = it.optString("title");
                    p.poster = it.optString("poster");
                    p.episodeIndex = it.optInt("episodeIndex", 0);
                    p.position = it.optLong("position", 0);
                    p.duration = it.optLong("duration", 0);
                    p.ts = it.optLong("ts", 0);
                    return p;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    public static ArrayList<Progress> getContinueWatching(Context ctx) {
        ArrayList<Progress> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp(ctx).getString(K_PROGRESS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);
                Progress p = new Progress();
                p.source = it.optString("source");
                p.id = it.optString("id");
                p.title = it.optString("title");
                p.poster = it.optString("poster");
                p.episodeIndex = it.optInt("episodeIndex", 0);
                p.position = it.optLong("position", 0);
                p.duration = it.optLong("duration", 0);
                p.ts = it.optLong("ts", 0);
                list.add(p);
            }
        } catch (Exception ignore) {
        }
        // 按 ts 倒序（不用 lambda：android.jar 的 LambdaMetafactory 不含 metafactory，javac 会失败）
        java.util.Collections.sort(list, new java.util.Comparator<Progress>() {
            @Override
            public int compare(Progress a, Progress b) {
                return Long.compare(b.ts, a.ts);
            }
        });
        return list;
    }

    public static void clearProgress(Context ctx, String source, String id) {
        try {
            JSONArray arr = new JSONArray(sp(ctx).getString(K_PROGRESS, "[]"));
            String k = key(source, id);
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);
                if (!key(it.optString("source"), it.optString("id")).equals(k)) out.put(it);
            }
            sp(ctx).edit().putString(K_PROGRESS, out.toString()).apply();
        } catch (Exception ignore) {
        }
    }

    public static void addFavorite(Context ctx, Favorite f) {
        try {
            JSONArray arr = new JSONArray(sp(ctx).getString(K_FAV, "[]"));
            String k = key(f.source, f.id);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);
                if (key(it.optString("source"), it.optString("id")).equals(k)) return; // 已存在
            }
            JSONObject o = new JSONObject();
            o.put("id", f.id);
            o.put("source", f.source);
            o.put("title", f.title);
            o.put("poster", f.poster);
            o.put("year", f.year);
            arr.put(o);
            sp(ctx).edit().putString(K_FAV, arr.toString()).apply();
        } catch (Exception ignore) {
        }
    }

    public static void removeFavorite(Context ctx, String id, String source) {
        try {
            JSONArray arr = new JSONArray(sp(ctx).getString(K_FAV, "[]"));
            String k = key(source, id);
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);
                if (!key(it.optString("source"), it.optString("id")).equals(k)) out.put(it);
            }
            sp(ctx).edit().putString(K_FAV, out.toString()).apply();
        } catch (Exception ignore) {
        }
    }

    public static boolean isFavorite(Context ctx, String id, String source) {
        try {
            JSONArray arr = new JSONArray(sp(ctx).getString(K_FAV, "[]"));
            String k = key(source, id);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);
                if (key(it.optString("source"), it.optString("id")).equals(k)) return true;
            }
        } catch (Exception ignore) {
        }
        return false;
    }

    public static ArrayList<Favorite> getFavorites(Context ctx) {
        ArrayList<Favorite> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp(ctx).getString(K_FAV, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);
                Favorite f = new Favorite();
                f.id = it.optString("id");
                f.source = it.optString("source");
                f.title = it.optString("title");
                f.poster = it.optString("poster");
                f.year = it.optString("year");
                list.add(f);
            }
        } catch (Exception ignore) {
        }
        return list;
    }

    public static void saveSkip(Context ctx, String source, String id, String config) {
        try {
            JSONArray arr = new JSONArray(sp(ctx).getString(K_SKIP, "[]"));
            String k = key(source, id);
            JSONObject o = new JSONObject();
            o.put("key", k);
            o.put("config", config);
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);
                if (!it.optString("key").equals(k)) out.put(it);
            }
            out.put(o);
            sp(ctx).edit().putString(K_SKIP, out.toString()).apply();
        } catch (Exception ignore) {
        }
    }

    public static String getSkip(Context ctx, String source, String id) {
        try {
            JSONArray arr = new JSONArray(sp(ctx).getString(K_SKIP, "[]"));
            String k = key(source, id);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);
                if (it.optString("key").equals(k)) return it.optString("config", "");
            }
        } catch (Exception ignore) {
        }
        return "";
    }

    /**
     * 直播源本地缓存。
     *
     * 【老板需求】"直播源刷新：点了后从新获取直播的源，已经下载的源可以本地化保存，
     * 默认优先读本地的源地址"。
     *
     * 直播源（key/name/url/ua/epg/channelNumber）序列化成 JSON 数组存进 SharedPreferences，
     * 进直播页时先读本地缓存（有就先用），同时后台刷新——
     * 服务端改配置时本地是旧的，但用户点「刷新」按钮可以强制拉新。
     */
    public static void saveLiveSources(Context ctx, ArrayList<LunaLive.Source> list) {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < list.size(); i++) {
                LunaLive.Source s = list.get(i);
                JSONObject o = new JSONObject();
                o.put("key", s.key);
                o.put("name", s.name);
                o.put("url", s.url);
                o.put("ua", s.ua);
                o.put("epg", s.epg);
                o.put("channelNumber", s.channelNumber);
                arr.put(o);
            }
            sp(ctx).edit().putString(K_LIVE_SOURCES, arr.toString()).apply();
        } catch (Throwable t) {
            Diag.e("store", "saveLiveSources failed: " + t.getMessage(), null);
        }
    }

    /** 读本地缓存的直播源（没有返回空 list）。 */
    public static ArrayList<LunaLive.Source> getLiveSources(Context ctx) {
        ArrayList<LunaLive.Source> out = new ArrayList<LunaLive.Source>();
        String raw = sp(ctx).getString(K_LIVE_SOURCES, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) continue;
                LunaLive.Source s = new LunaLive.Source();
                s.key = it.opt("key") == null ? null : it.getString("key");
                s.name = it.opt("name") == null ? null : it.getString("name");
                s.url = it.opt("url") == null ? null : it.getString("url");
                s.ua = it.opt("ua") == null ? null : it.getString("ua");
                s.epg = it.opt("epg") == null ? null : it.getString("epg");
                s.channelNumber = it.optInt("channelNumber");
                out.add(s);
            }
        } catch (Throwable t) {
            Diag.e("store", "getLiveSources failed: " + t.getMessage(), null);
        }
        return out;
    }

    /** 本地有没有缓存过直播源。 */
    public static boolean hasLiveSourcesCache(Context ctx) {
        String raw = sp(ctx).getString(K_LIVE_SOURCES, null);
        return raw != null && !raw.isEmpty();
    }

    /**
     * 清空当前账号的本地数据（退出登录时调用）。
     *
     * 播放进度与收藏是「按账号」的数据：服务端 redis 里存了一份（多端同步），
     * 本地也存了一份（离线可用）。换账号登录时若不清，另一位用户的
     * 「继续观看 / 我的收藏」会串到新账号下。所以退出时本地这份要一起清掉。
     */
    public static void clearAccountData(Context ctx) {
        try {
            sp(ctx).edit()
                    .remove(K_PROGRESS)
                    .remove(K_FAV)
                    .remove(K_SKIP)
                    .remove(K_LIVE_SOURCES)
                    .apply();
        } catch (Exception ignore) {
        }
    }
}
