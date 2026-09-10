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

    @Test public void resolvesCommonCustomSchemesWithoutBundledHttpsFallback() {
        assertEquals("com.instagram.android", CustomSchemeTargetResolver.findTargetPackageForScheme("instagram"));
        assertEquals("org.telegram.messenger", CustomSchemeTargetResolver.findTargetPackageForScheme("tg"));
        assertEquals("com.google.android.youtube", CustomSchemeTargetResolver.findTargetPackageForScheme("vnd.youtube"));
        assertNull(CustomSchemeTargetResolver.findTargetPackageForUri("https", "www.xiaohongshu.com"));
        assertNull(CustomSchemeTargetResolver.findTargetPackageForUri("https", "x.com"));
        assertNull(CustomSchemeTargetResolver.findTargetPackageForUri("https", "t.me"));
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

    @Test public void httpsHasNoBundledFallback() {
        assertNull(CustomSchemeTargetResolver.findTargetPackageForUri("https", "www.bilibili.com"));
        assertEquals(Collections.emptyList(),
                CustomSchemeTargetResolver.findTargetPackagesForUri(
                        "https", "www.bilibili.com", Collections.<String, List<String>>emptyMap()));
    }

    @Test public void savedHttpsRuleIsUsed() {
        assertEquals("com.twitter.android", CustomSchemeTargetResolver.findTargetPackageForUri(
                "https", "x.com", Collections.singletonMap("x.com", "com.twitter.android")));
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

    @Test public void sharedBilibiliHostSelectsTheFrozenOfficialPackage() {
        Map<String, List<String>> candidates = new LinkedHashMap<>();
        candidates.put("bilibili.com", Arrays.asList("com.example.piliplus", "tv.danmaku.bili"));
        assertEquals("tv.danmaku.bili", NativeBridgePolicy.selectCandidate(
                CustomSchemeTargetResolver.findTargetPackagesForUri(
                        "https", "www.bilibili.com", candidates),
                Collections.singleton("tv.danmaku.bili")));
    }

    @Test public void ignoresUnknownAndNonAppHosts() {
        assertNull(CustomSchemeTargetResolver.findTargetPackageForScheme("unknownapp"));
        assertEquals("com.xingin.xhs", CustomSchemeTargetResolver.findTargetPackageForScheme("XHSDiscover"));
        assertNull(CustomSchemeTargetResolver.findTargetPackageForScheme(null));
        assertNull(CustomSchemeTargetResolver.findTargetPackageForUri("https", "example.com"));
        assertNull(CustomSchemeTargetResolver.findTargetPackageForUri("ftp", "www.xiaohongshu.com"));
    }


}

