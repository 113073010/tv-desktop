package com.dsh.tvdesk.radio;

import java.util.ArrayList;
import java.util.List;

/** 电台清单（回到 v21 的 15 个频道 / 5 个分类） */
public class Stations {

    public static class S {
        public final String name, url, cat, fm;
        public S(String n, String u, String c, String f) { name = n; url = u; cat = c; fm = f; }
    }

    public static final String CAT_NEWS  = "新闻广播";
    public static final String CAT_MUSIC = "音乐电台";
    public static final String CAT_FIN   = "财经电台";
    public static final String CAT_KIDS  = "少儿故事";
    public static final String CAT_ALL   = "全部电台";

    public static final String[] CATS = {CAT_NEWS, CAT_MUSIC, CAT_FIN, CAT_KIDS, CAT_ALL};

    public static List<S> all() {
        List<S> l = new ArrayList<S>();

        // ---------- 新闻广播 ----------
        l.add(new S("中国之声", "https://ngcdn001.cnr.cn/live/zgzs/index.m3u8", CAT_NEWS, "FM 106.1"));
        l.add(new S("经济之声", "https://ngcdn002.cnr.cn/live/jjzs/index.m3u8", CAT_NEWS, "FM 96.6"));
        l.add(new S("CCTV-13 新闻伴音", "https://piccpndali.v.myalicdn.com/audio/cctv13_2.m3u8", CAT_NEWS, "电视伴音"));
        l.add(new S("环球资讯广播", "https://sk.cri.cn/905.m3u8", CAT_NEWS, "FM 90.5"));
        l.add(new S("凤凰资讯台", "http://playtv-live.ifeng.com/live/06OLEEWQKN4_audio.m3u8", CAT_NEWS, "网络直播"));

        // ---------- 音乐电台 ----------
        l.add(new S("音乐之声", "https://ngcdn001.cnr.cn/live/yyzs/index.m3u8", CAT_MUSIC, "FM 90.0"));
        l.add(new S("广东音乐之声", "https://lhttp.qtfm.cn/live/1260/64k.mp3", CAT_MUSIC, "FM 99.3"));
        l.add(new S("怀集音乐之声", "https://lhttp.qingting.fm/live/4804/64k.mp3", CAT_MUSIC, "FM 94.9"));
        l.add(new S("华语金曲 500", "https://lhttp.qtfm.cn/live/5022308/64k.mp3", CAT_MUSIC, "网络电台"));
        l.add(new S("清晨音乐台", "http://lhttp.qingting.fm/live/4915/64k.mp3", CAT_MUSIC, "网络电台"));
        l.add(new S("AsiaFM 亚洲", "http://asiafm.hk:8000/asiahd", CAT_MUSIC, "网络电台"));

        // ---------- 财经电台 ----------
        l.add(new S("CCTV-2 财经伴音", "https://piccpndali.v.myalicdn.com/audio/cctv2_2.m3u8", CAT_FIN, "电视伴音"));
        l.add(new S("经济之声·财经", "https://ngcdn002.cnr.cn/live/jjzs/index.m3u8", CAT_FIN, "FM 96.6"));

        // ---------- 少儿故事 ----------
        l.add(new S("听云故事台", "https://m.tyradio.uk/radio.mp3", CAT_KIDS, "网络电台"));
        l.add(new S("童话故事广播", "https://lhttp.qtfm.cn/live/20500182/64k.mp3", CAT_KIDS, "网络电台"));

        return l;
    }

    public static List<S> byCat(List<S> src, String cat) {
        if (cat == null || cat.equals(CAT_ALL)) return new ArrayList<S>(src);
        List<S> out = new ArrayList<S>();
        for (S s : src) if (cat.equals(s.cat)) out.add(s);
        return out;
    }

    public static S byName(List<S> src, String name) {
        if (name == null) return null;
        for (S s : src) if (name.equals(s.name)) return s;
        return null;
    }
}
