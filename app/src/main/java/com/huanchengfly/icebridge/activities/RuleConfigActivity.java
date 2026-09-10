package com.huanchengfly.icebridge.activities;

import android.content.Intent;
import android.os.Bundle;

import com.huanchengfly.icebridge.ui.MiuixRuleConfigScreen;

public class RuleConfigActivity extends BaseActivity {
    public static final int REQUEST_IMPORT_RULES = 0x4101;
    public static final int REQUEST_EXPORT_RULES = 0x4102;

    public interface RuleTransferResultListener {
        void onRuleTransferResult(int requestCode, int resultCode, Intent data);
    }

    private RuleTransferResultListener ruleTransferResultListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MiuixRuleConfigScreen.install(this);
    }

    public void setRuleTransferResultListener(RuleTransferResultListener listener) {
        ruleTransferResultListener = listener;
    }

    public void launchRuleImport(Intent intent) {
        startActivityForResult(intent, REQUEST_IMPORT_RULES);
    }

    public void launchRuleExport(Intent intent) {
        startActivityForResult(intent, REQUEST_EXPORT_RULES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == REQUEST_IMPORT_RULES || requestCode == REQUEST_EXPORT_RULES)
                && ruleTransferResultListener != null) {
            ruleTransferResultListener.onRuleTransferResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected int getLayoutId() {
        return NO_LAYOUT;
    }
}
