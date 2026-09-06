package com.huanchengfly.icebridge.android16;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class CustomSchemeTargetResolverTest {
    @Test public void resolvesXiaohongshuScheme() {
        assertEquals("com.xingin.xhs", CustomSchemeTargetResolver.findTargetPackageForScheme("xhsdiscover"));
    }

    @Test public void resolvesCommonSchemesAndHttpsHosts() {
        assertEquals("com.instagram.android", CustomSchemeTargetResolver.findTargetPackageForScheme("instagram"));
        assertEquals("org.telegram.messenger", CustomSchemeTargetResolver.findTargetPackageForScheme("tg"));
        assertEquals("com.google.android.youtube", CustomSchemeTargetResolver.findTargetPackageForScheme("vnd.youtube"));
        assertEquals("com.xingin.xhs", CustomSchemeTargetResolver.findTargetPackageForUri("https", "www.xiaohongshu.com"));
        assertEquals("com.twitter.android", CustomSchemeTargetResolver.findTargetPackageForUri("https", "x.com"));
        assertEquals("org.telegram.messenger", CustomSchemeTargetResolver.findTargetPackageForUri("https", "t.me"));
    }

    @Test public void directSchemeResolutionNeverClaimsHttpsLinks() {
        assertEquals("com.eg.android.AlipayGphone",
                CustomSchemeTargetResolver.findTargetPackageForCustomScheme("alipays"));
        assertEquals("com.xingin.xhs",
                CustomSchemeTargetResolver.findTargetPackageForCustomScheme("xhsdiscover"));
        assertNull(CustomSchemeTargetResolver.findTargetPackageForCustomScheme("https"));
        assertNull(CustomSchemeTargetResolver.findTargetPackageForCustomScheme("http"));
    }
    @Test public void resolvesAlipaySchemes() {
        assertEquals("com.eg.android.AlipayGphone",
                CustomSchemeTargetResolver.findTargetPackageForScheme("alipays"));
        assertEquals("com.eg.android.AlipayGphone",
                CustomSchemeTargetResolver.findTargetPackageForScheme("alipay"));
    }

    @Test public void resolvesSupportedMainlandAppLinks() {
        assertEquals("com.sina.weibo", CustomSchemeTargetResolver.findTargetPackageForUri("https", "m.weibo.cn"));
        assertEquals("com.zhihu.android", CustomSchemeTargetResolver.findTargetPackageForUri("https", "www.zhihu.com"));
        assertEquals("com.eg.android.AlipayGphone", CustomSchemeTargetResolver.findTargetPackageForUri("https", "www.alipay.com"));
    }

    @Test public void resolvesSupportedInternationalAppLinks() {
        assertEquals("com.google.android.youtube", CustomSchemeTargetResolver.findTargetPackageForUri("https", "youtu.be"));
        assertEquals("com.instagram.android", CustomSchemeTargetResolver.findTargetPackageForUri("https", "www.instagram.com"));
        assertEquals("org.telegram.messenger", CustomSchemeTargetResolver.findTargetPackageForUri("https", "telegram.me"));
    }


    @Test public void savedRuleSnapshotOverridesBuiltInHostTarget() {
        assertEquals("com.example.reader", CustomSchemeTargetResolver.findTargetPackageForUri(
                "https", "www.x.com", Collections.singletonMap("x.com", "com.example.reader")));
    }

    @Test public void retainsOrderedCandidatesForSharedHost() {
        Map<String, List<String>> candidates = new LinkedHashMap<>();
        candidates.put("bilibili.com", Arrays.asList("com.example.piliplus", "tv.danmaku.bili"));
        assertEquals("com.example.piliplus", CustomSchemeTargetResolver.findFirstTargetPackageForUri("https", "www.bilibili.com", candidates));
        assertEquals(Arrays.asList("com.example.piliplus", "tv.danmaku.bili"),
                CustomSchemeTargetResolver.findTargetPackagesForUri("https", "www.bilibili.com", candidates));
    }

    @Test public void ignoresUnknownAndNonAppHosts() {
        assertNull(CustomSchemeTargetResolver.findTargetPackageForScheme("unknownapp"));
        assertEquals("com.xingin.xhs", CustomSchemeTargetResolver.findTargetPackageForScheme("XHSDiscover"));
        assertNull(CustomSchemeTargetResolver.findTargetPackageForScheme(null));
        assertNull(CustomSchemeTargetResolver.findTargetPackageForUri("https", "example.com"));
        assertNull(CustomSchemeTargetResolver.findTargetPackageForUri("ftp", "www.xiaohongshu.com"));
    }


}

