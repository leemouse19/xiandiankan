package com.tvshell.moontv;

import java.io.Serializable;

/** 单集（/api/detail -> episodes[]）。url 为可直接播放的 m3u8/mp4。 */
public class Episode implements Serializable {
    public String episode;
    public String url;
}
