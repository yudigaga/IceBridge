package com.huanchengfly.icebridge.automation;

import android.net.Uri;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Static, audited compatibility catalog derived from Fluid Cloud rules.json.
 * IceBridge accepts deterministic host routing and fixed deep-link conversion only. It never
 * executes Fluid Cloud JavaScript, shell, arbitrary Intent text, custom regex, or remote actions.
 */
public final class FluidCloudRuleCatalog {
    private static final Map<String, String> HOST_TO_PACKAGE;
    private static final Map<String, List<String>> HOST_TO_PACKAGES;
    private static final Map<String, String> HANDOFF_SUMMARIES;

    static {
        Map<String, List<String>> candidates = new LinkedHashMap<>();
        Map<String, String> hosts = new LinkedHashMap<>();
        putHosts(candidates, "com.xingin.xhs", "xiaohongshu.com", "xhslink.com");
        putHosts(candidates, "com.sina.weibo", "weibo.com", "weibo.cn", "sinaweibo.cn");
        putHosts(candidates, "com.zhihu.android", "zhihu.com", "zhimg.com");
        putHosts(candidates, "com.eg.android.AlipayGphone", "alipay.com", "alipay.cn");
        putHosts(candidates, "com.twitter.android", "x.com", "twitter.com");
        putHosts(candidates, "com.google.android.youtube", "youtube.com", "youtu.be");
        putHosts(candidates, "com.instagram.android", "instagram.com");
        putHosts(candidates, "org.telegram.messenger", "t.me", "telegram.me");
        putHosts(candidates, "tv.danmaku.bili", "bilibili.com", "b23.tv", "bilibili.cn", "bilibili.tv", "bili22.cn", "bili23.cn", "bili33.cn", "bili2233.cn", "bilibili.smgbb.cn", "m.acg.tv");
        // Audited package and URL rules imported from Fluid Cloud.
        putHosts(candidates, "com.ylmf.androidclient", "115.com", "115cdn.com");
        putHosts(candidates, "com.mfcloudcalculate.networkdisk", "123pan.com", "123pan.cn");
        putHosts(candidates, "com.alibaba.wireless", "1688.com");
        putHosts(candidates, "com.jingdong.app.mall", "jd.com", "3.cn");
        putHosts(candidates, "com.quark.browser", "pan.quark.cn");
        putHosts(candidates, "com.kurogame.kjq", "kurobbs.com");
        putHosts(candidates, "com.smile.gifmaker", "kuaishou.com");
        putHosts(candidates, "com.ss.android.ugc.aweme", "douyin.com");
        putHosts(candidates, "com.xunmeng.pinduoduo", "yangkeduo.com", "y4n.cn", "srgnnmsrg.com", "pinduoduo.com");
        putHosts(candidates, "com.taobao.taobao", "tb.cn");
        putHosts(candidates, "com.baidu.netdisk", "pan.baidu.com");
        putHosts(candidates, "com.baidu.tieba", "tieba.baidu.com");
        putHosts(candidates, "com.mihoyo.hyperion", "miyoushe.com");
        putHosts(candidates, "com.sankuai.meituan", "dpurl.cn");
        putHosts(candidates, "com.coolapk.market", "coolapk.com");
        putHosts(candidates, "com.alicloud.databox", "alipan.com");
        // Fluid Cloud has valid alternatives for the same host. Keep them as ordered candidates
        // instead of silently overwriting one application with another in a flat map.
        putHosts(candidates, "com.example.piliplus", "bilibili.com", "b23.tv", "bilibili.cn", "bilibili.tv", "bili22.cn", "bili23.cn", "bili33.cn", "bili2233.cn", "bilibili.smgbb.cn", "m.acg.tv");
        putHosts(candidates, "com.sankuai.meituan.takeoutnew", "dpurl.cn");
        HOST_TO_PACKAGES = immutableCandidates(candidates);
        for (Map.Entry<String, List<String>> entry : HOST_TO_PACKAGES.entrySet()) {
            if (!entry.getValue().isEmpty()) hosts.put(entry.getKey(), entry.getValue().get(0));
        }
        HOST_TO_PACKAGE = Collections.unmodifiableMap(hosts);
        Map<String, String> summaries = new LinkedHashMap<>();
        for (String packageName : new String[] {"com.ylmf.androidclient", "com.mfcloudcalculate.networkdisk", "com.alibaba.wireless", "com.jingdong.app.mall", "com.quark.browser", "com.kurogame.kjq", "com.smile.gifmaker", "com.taobao.taobao", "com.taobao.idlefish", "com.baidu.netdisk", "com.baidu.tieba", "com.mihoyo.hyperion", "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew", "com.alicloud.databox"}) summaries.put(packageName, "\u5df2\u5bfc\u5165\u6d41\u4e91\u4f53\u517c\u5bb9\u6df1\u94fe\u8f6c\u6362");
        HANDOFF_SUMMARIES = Collections.unmodifiableMap(summaries);
    }

    private FluidCloudRuleCatalog() {}
    /** First candidate compatibility projection used by legacy callers. */
    public static Map<String, String> builtInHostMappings() { return HOST_TO_PACKAGE; }

    /** Ordered candidates retain distinct Fluid Cloud rules sharing the same host. */
    public static Map<String, List<String>> builtInHostCandidates() { return HOST_TO_PACKAGES; }

    /** Separate audited entries used by the rule editor and effective-rule persistence. */
    public static List<LinkRuleEntry> builtInRuleEntries() {
        List<LinkRuleEntry> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : HOST_TO_PACKAGES.entrySet()) {
            for (String packageName : entry.getValue()) {
                boolean conversion = HANDOFF_SUMMARIES.containsKey(packageName);
                result.add(new LinkRuleEntry(entry.getKey(), packageName,
                        LinkRuleEntry.SOURCE_BUILT_IN,
                        conversion ? LinkRuleEntry.CONVERSION_FIXED_DEEP_LINK : LinkRuleEntry.CONVERSION_NONE,
                        conversion ? HANDOFF_SUMMARIES.get(packageName) : ""));
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static String handoffSummary(String packageName) { return HANDOFF_SUMMARIES.get(packageName); }

    /** Returns the original URI unless a reviewed Fluid Cloud conversion matches this target. */
    public static Uri rewriteForTarget(Uri source, String targetPackage) {
        if (source == null || targetPackage == null || !isWeb(source)) return source;
        String host = normalizedHost(source);
        if (host == null) return source;
        try {
            if (isHost(host, "115.com") || isHost(host, "115cdn.com")) return onlyFor(targetPackage, "com.ylmf.androidclient", "oof.disk://openurl/" + Uri.encode(stripFragment(source).toString()), source);
            if (isHost(host, "123pan.com") || isHost(host, "123pan.cn")) return share123(source, targetPackage);
            if (isHost(host, "1688.com")) return onlyFor(targetPackage, "com.alibaba.wireless", "wireless1688://ma.m.1688.com/plugin?url=" + Uri.encode(source.toString()), source);
            if (isHost(host, "jd.com") || isHost(host, "3.cn")) return onlyFor(targetPackage, "com.jingdong.app.mall", "openapp.jdmobile://virtual?params=" + Uri.encode("{'category':'jump','des':'m','url':'" + source + "'}"), source);
            if (isHost(host, "pan.quark.cn")) return onlyFor(targetPackage, "com.quark.browser", "qklink://www.uc.cn/b20b84fd735a8dd3f7541129bacc4e9a?action=open_url&url=" + Uri.encode(source.toString()), source);
            if (isHost(host, "kurobbs.com")) return queryLink(source, targetPackage, "com.kurogame.kjq", "postId", "kjq://home/post/postDetail?postId=");
            if (isHost(host, "kuaishou.com")) return onlyFor(targetPackage, "com.smile.gifmaker", "kwai://webview?url=" + Uri.encode(source.toString()), source);
            if (isHost(host, "tb.cn")) return taobaoLink(source, targetPackage);
            if (isHost(host, "pan.baidu.com")) return baiduPanLink(source, targetPackage);
            if (isHost(host, "tieba.baidu.com")) return pathLink(source, targetPackage, "com.baidu.tieba", "/p/", "com.baidu.tieba://unidispatch/pb?tid=");
            if (isHost(host, "miyoushe.com")) return onlyFor(targetPackage, "com.mihoyo.hyperion", "mihoyobbs://webview?link=" + Uri.encode(source.toString()), source);
            if (isHost(host, "dpurl.cn")) return meituanLink(source, targetPackage);
            if (isHost(host, "alipan.com")) return aliPanLink(source, targetPackage);
        } catch (Throwable ignored) { }
        return source;
    }

    private static Uri share123(Uri source, String target) { String key = segmentAfter(source, "/123pan/"); if (key == null) key = segmentAfter(source, "/s/"); return key == null ? source : onlyFor(target, "com.mfcloudcalculate.networkdisk", "pan://umeng.com/share/list?sharePwd=" + Uri.encode(empty(source.getQueryParameter("pwd"))) + "&_sdk_=umeng&action=share_list&shareKey=" + Uri.encode(key), source); }
    private static Uri taobaoLink(Uri source, String target) { if ("com.taobao.taobao".equals(target)) return Uri.parse("tbopen://m.taobao.com/tbopen/index.html?h5Url=" + Uri.encode(source.toString())); if ("com.taobao.idlefish".equals(target)) return Uri.parse("fleamarket://2.taobao.com/onepiece?h5Url=" + Uri.encode(source.toString())); return source; }
    private static Uri baiduPanLink(Uri source, String target) { String share = segmentAfter(source, "/1"); if (share == null || !"com.baidu.netdisk".equals(target)) return source; String value = "bdnetdisk://n/action.SHARE_LINK?m_n_v=8.3.0&surl=" + Uri.encode(share); String pwd = source.getQueryParameter("pwd"); return Uri.parse(pwd == null || pwd.isEmpty() ? value : value + "&pwd=" + Uri.encode(pwd)); }
    private static Uri meituanLink(Uri source, String target) { if ("com.sankuai.meituan".equals(target)) return Uri.parse("imeituan://www.meituan.com/web?notitlebar=1&url=" + Uri.encode(source.toString())); if ("com.sankuai.meituan.takeoutnew".equals(target)) return Uri.parse("meituanwaimai://waimai.meituan.com/browser?inner_url=" + Uri.encode(source.toString())); return source; }
    private static Uri aliPanLink(Uri source, String target) { String share = segmentAfter(source, "/s/"); boolean friend = false; if (share == null) { share = segmentAfter(source, "/t/"); friend = share != null; } if (share == null || !"com.alicloud.databox".equals(target)) return source; String value = (friend ? "smartdrive://friendshare/browse?shareId=" : "smartdrive://share/browse?shareId=") + Uri.encode(share); String pwd = source.getQueryParameter("pwd"); return Uri.parse(pwd == null || pwd.isEmpty() ? value : value + "&sharePwd=" + Uri.encode(pwd)); }
    private static Uri queryLink(Uri source, String target, String expected, String parameter, String prefix) { String value = source.getQueryParameter(parameter); return value == null ? source : onlyFor(target, expected, prefix + Uri.encode(value), source); }
    private static Uri pathLink(Uri source, String target, String expected, String marker, String prefix) { String value = segmentAfter(source, marker); return value == null ? source : onlyFor(target, expected, prefix + Uri.encode(value), source); }
    private static Uri onlyFor(String target, String expected, String deepLink, Uri fallback) { return expected.equals(target) ? Uri.parse(deepLink) : fallback; }
    private static String segmentAfter(Uri uri, String marker) { String path = uri.getPath(); if (path == null) return null; int index = path.indexOf(marker); if (index < 0) return null; String value = path.substring(index + marker.length()); int slash = value.indexOf('/'); if (slash >= 0) value = value.substring(0, slash); return value.isEmpty() ? null : value; }
    private static Uri stripFragment(Uri uri) { return uri.buildUpon().fragment(null).build(); }
    private static boolean isWeb(Uri uri) { String scheme = uri.getScheme(); return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme); }
    private static String normalizedHost(Uri uri) { String host = uri.getHost(); return host == null ? null : host.toLowerCase(Locale.ROOT); }
    private static boolean isHost(String host, String root) { return root.equals(host) || host.endsWith("." + root); }
    private static String empty(String value) { return value == null ? "" : value; }
    private static void putHosts(Map<String, List<String>> mappings, String packageName, String... hosts) {
        if (packageName == null || hosts == null) return;
        for (String host : hosts) {
            if (host == null) continue;
            List<String> values = mappings.get(host);
            if (values == null) {
                values = new ArrayList<>();
                mappings.put(host, values);
            }
            if (!values.contains(packageName)) values.add(packageName);
        }
    }

    private static Map<String, List<String>> immutableCandidates(Map<String, List<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            Set<String> unique = new LinkedHashSet<>(entry.getValue());
            result.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(unique)));
        }
        return Collections.unmodifiableMap(result);
    }
}