package com.huanchengfly.icebridge.base;

import org.litepal.LitePalApplication;

import com.huanchengfly.icebridge.automation.OnlineLinkRuleRepository;
import com.huanchengfly.icebridge.services.ClipboardLinkService;

public class MyApplication extends LitePalApplication {
    private static MyApplication instance;

    public static MyApplication getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        instance = this;
        super.onCreate();
        OnlineLinkRuleRepository.maybeRefreshAsync(this);
        ClipboardLinkService.update(this);
    }
}
