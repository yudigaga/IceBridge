package com.huanchengfly.icebridge.automation;

import android.net.Uri;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FluidCloudRuleCatalogTest {
    @Test
    public void importsAuditedFluidCloudHostRules() {
        assertEquals("com.jingdong.app.mall", FluidCloudRuleCatalog.builtInHostMappings().get("jd.com"));
        assertEquals("com.baidu.netdisk", FluidCloudRuleCatalog.builtInHostMappings().get("pan.baidu.com"));
        assertEquals("com.smile.gifmaker", FluidCloudRuleCatalog.builtInHostMappings().get("kuaishou.com"));
    }

    @Test
    public void preservesDistinctCandidatesForTheSameFluidCloudHost() {
        assertEquals("tv.danmaku.bili", FluidCloudRuleCatalog.builtInHostCandidates().get("bilibili.com").get(0));
        assertTrue(FluidCloudRuleCatalog.builtInHostCandidates().get("bilibili.com").contains("com.example.piliplus"));
        assertEquals("com.sankuai.meituan", FluidCloudRuleCatalog.builtInHostCandidates().get("dpurl.cn").get(0));
        assertTrue(FluidCloudRuleCatalog.builtInHostCandidates().get("dpurl.cn").contains("com.sankuai.meituan.takeoutnew"));
    }

    @Test
    public void normalizesAllSupportedSeparatorsAndDeduplicates() {
        assertEquals(java.util.Arrays.asList("example.com", "news.example.com"),
                RuleConfigRepository.normalizeUserHosts(
                        "https://example.com/a\uFF0Cexample.com; news.example.com\uFF1Bhttps://news.example.com/post"));
    }

    @Test
    public void rewrites123PanNativeSharePath() {
        Uri source = Uri.parse("https://1815316052.share.123pan.cn/123pan/krztVv-hkn2");
        Uri handoff = FluidCloudRuleCatalog.rewriteForTarget(source,
                "com.mfcloudcalculate.networkdisk");
        assertEquals("pan", handoff.getScheme());
        assertEquals("umeng.com", handoff.getHost());
        assertEquals("krztVv-hkn2", handoff.getQueryParameter("shareKey"));
        assertEquals("share_list", handoff.getQueryParameter("action"));
    }

    @Test
    public void keepsUnsupported123PanPathUnchanged() {
        Uri source = Uri.parse("https://123pan.com/other/krztVv-hkn2");
        assertEquals(source, FluidCloudRuleCatalog.rewriteForTarget(source,
                "com.mfcloudcalculate.networkdisk"));
    }

    @Test
    public void exposesOnlyAuditedDeepLinkRules() {
        assertEquals("\u5df2\u5bfc\u5165\u6d41\u4e91\u4f53\u517c\u5bb9\u6df1\u94fe\u8f6c\u6362", FluidCloudRuleCatalog.handoffSummary("com.jingdong.app.mall"));
        assertEquals(null, FluidCloudRuleCatalog.handoffSummary("com.example.untrusted"));
    }
}
