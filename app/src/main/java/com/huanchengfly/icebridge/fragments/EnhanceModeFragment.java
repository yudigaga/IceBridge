package com.huanchengfly.icebridge.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.preference.SwitchPreference;
import androidx.preference.Preference;
import androidx.preference.EditTextPreference;

import com.huanchengfly.icebridge.R;
import com.huanchengfly.icebridge.SystemServerConfigContract;
import com.huanchengfly.icebridge.databases.AppConfig;
import com.huanchengfly.icebridge.automation.LinkAutomationConfig;
import com.huanchengfly.icebridge.services.ClipboardLinkService;
import com.huanchengfly.icebridge.utils.DiagnosticLogExporter;
import com.huanchengfly.icebridge.utils.RecentTaskVisibility;
import com.huanchengfly.icebridge.utils.Util;

import org.litepal.LitePal;

public class EnhanceModeFragment extends PreferencesFragment {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName("preferences");
        addPreferencesFromResource(R.xml.preferences);
        refreshStatus();
        SwitchPreference enhancePreference = findPreference(R.string.key_enhance_mode_status);
        SwitchPreference debugLoggingPreference = findPreference(R.string.key_module_debug_logging);
        SwitchPreference xhsRecoveryPreference = findPreference(R.string.key_auto_link_bridge);
        SwitchPreference autoOpenPreference = findPreference(R.string.key_auto_open_links);
        SwitchPreference nativeLinkCapturePreference = findPreference(R.string.key_native_link_capture);
        SwitchPreference paymentSdkBridgePreference = findPreference(R.string.key_payment_sdk_bridge);
        SwitchPreference hideFromRecentsPreference = findPreference(R.string.key_hide_bridge_from_recents);
        SwitchPreference onlyFrozenPreference = findPreference(R.string.key_only_frozen_link_targets);
        SwitchPreference browserFallbackPreference = findPreference(R.string.key_link_browser_fallback);
        SwitchPreference clipboardPreference = findPreference(R.string.key_clipboard_auto_open);
        EditTextPreference customMappingsPreference = findPreference(R.string.key_custom_url_mappings);
        Preference exportDiagnosticLogs = findPreference(R.string.key_export_diagnostic_logs);
        Preference permissionStatus = findPreference(R.string.key_permission_status);
        permissionStatus.setOnPreferenceClickListener(preference -> {
            startActivity(new Intent(getAttachContext(), com.huanchengfly.icebridge.activities.PermissionActivity.class));
            return true;
        });
        exportDiagnosticLogs.setOnPreferenceClickListener(preference -> {
            DiagnosticLogExporter.export(getAttachContext());
            return true;
        });
        xhsRecoveryPreference.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean enabled = (boolean) newValue;
            new AppConfig("xhs_resolve_recovery", enabled ? "1" : "0")
                    .saveOrUpdate("key = ?", "xhs_resolve_recovery");
            if (!enabled) {
                autoOpenPreference.setChecked(false);
                new AppConfig(SystemServerConfigContract.KEY_AUTO_OPEN_LINKS, "0")
                        .saveOrUpdate("key = ?", SystemServerConfigContract.KEY_AUTO_OPEN_LINKS);
                nativeLinkCapturePreference.setChecked(false);
                new AppConfig(SystemServerConfigContract.KEY_NATIVE_LINK_CAPTURE, "0")
                        .saveOrUpdate("key = ?", SystemServerConfigContract.KEY_NATIVE_LINK_CAPTURE);
                paymentSdkBridgePreference.setChecked(false);
                new AppConfig(SystemServerConfigContract.KEY_PAYMENT_SDK_BRIDGE, "0")
                        .saveOrUpdate("key = ?", SystemServerConfigContract.KEY_PAYMENT_SDK_BRIDGE);
            }
            return true;
        });
        autoOpenPreference.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean enabled = (boolean) newValue;
            if (enabled && !xhsRecoveryPreference.isChecked()) {
                xhsRecoveryPreference.setChecked(true);
                new AppConfig("xhs_resolve_recovery", "1")
                        .saveOrUpdate("key = ?", "xhs_resolve_recovery");
            }
            new AppConfig(SystemServerConfigContract.KEY_AUTO_OPEN_LINKS, enabled ? "1" : "0")
                    .saveOrUpdate("key = ?", SystemServerConfigContract.KEY_AUTO_OPEN_LINKS);
            return true;
        });
        nativeLinkCapturePreference.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean enabled = (boolean) newValue;
            if (enabled && !xhsRecoveryPreference.isChecked()) {
                xhsRecoveryPreference.setChecked(true);
                new AppConfig(SystemServerConfigContract.KEY_XHS_RECOVERY, "1")
                        .saveOrUpdate("key = ?", SystemServerConfigContract.KEY_XHS_RECOVERY);
            }
            if (enabled && !autoOpenPreference.isChecked()) {
                autoOpenPreference.setChecked(true);
                new AppConfig(SystemServerConfigContract.KEY_AUTO_OPEN_LINKS, "1")
                        .saveOrUpdate("key = ?", SystemServerConfigContract.KEY_AUTO_OPEN_LINKS);
            }
            new AppConfig(SystemServerConfigContract.KEY_NATIVE_LINK_CAPTURE, enabled ? "1" : "0")
                    .saveOrUpdate("key = ?", SystemServerConfigContract.KEY_NATIVE_LINK_CAPTURE);
            return true;
        });
        paymentSdkBridgePreference.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean enabled = (boolean) newValue;
            if (enabled && !xhsRecoveryPreference.isChecked()) {
                xhsRecoveryPreference.setChecked(true);
                new AppConfig(SystemServerConfigContract.KEY_XHS_RECOVERY, "1")
                        .saveOrUpdate("key = ?", SystemServerConfigContract.KEY_XHS_RECOVERY);
            }
            if (enabled && !autoOpenPreference.isChecked()) {
                autoOpenPreference.setChecked(true);
                new AppConfig(SystemServerConfigContract.KEY_AUTO_OPEN_LINKS, "1")
                        .saveOrUpdate("key = ?", SystemServerConfigContract.KEY_AUTO_OPEN_LINKS);
            }
            new AppConfig(SystemServerConfigContract.KEY_PAYMENT_SDK_BRIDGE, enabled ? "1" : "0")
                    .saveOrUpdate("key = ?", SystemServerConfigContract.KEY_PAYMENT_SDK_BRIDGE);
            return true;
        });
        hideFromRecentsPreference.setOnPreferenceChangeListener((preference, newValue) -> {
            RecentTaskVisibility.apply(requireActivity(), (boolean) newValue);
            return true;
        });
        onlyFrozenPreference.setOnPreferenceChangeListener((preference, newValue) -> true);
        browserFallbackPreference.setOnPreferenceChangeListener((preference, newValue) -> true);
        clipboardPreference.setOnPreferenceChangeListener((preference, newValue) -> {
            new Handler(Looper.getMainLooper()).post(() -> ClipboardLinkService.update(getAttachContext()));
            return true;
        });
        customMappingsPreference.setOnPreferenceChangeListener((preference, newValue) -> {
            String mappings = newValue == null ? "" : newValue.toString().trim();
            // Parsing and package validation are repeated by the automation controller before use.
            preference.setSummary(mappings.isEmpty()
                    ? getString(R.string.summary_custom_url_mappings)
                    : mappings);
            return true;
        });
        String savedMappings = customMappingsPreference.getText();
        if (savedMappings != null && !savedMappings.trim().isEmpty()) {
            customMappingsPreference.setSummary(savedMappings);
        }
        ClipboardLinkService.update(getAttachContext());
        debugLoggingPreference.setOnPreferenceChangeListener((preference, newValue) -> {
            new AppConfig("module_debug_logging", (boolean) newValue ? "1" : "0")
                    .saveOrUpdate("key = ?", "module_debug_logging");
            return true;
        });
        enhancePreference.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean enabled = (boolean) newValue;
            new AppConfig("enhance_mode_enabled", enabled ? "1" : "0").saveOrUpdate("key = ?", "enhance_mode_enabled");
            refreshStatus();
            return true;
        });
    }

    private void refreshStatus() {
        SwitchPreference enhancePreference = findPreference(R.string.key_enhance_mode_status);
        if (isXposedActive()) {
            if (isEnabled()) {
                enhancePreference.setSummary(R.string.summary_enhance_mode_active);
            } else {
                enhancePreference.setSummary(R.string.summary_enhance_mode_active_not_enable);
            }
        } else {
            enhancePreference.setSummary(R.string.summary_enhance_mode);
        }
        enhancePreference.setEnabled(true);
        enhancePreference.setChecked(isEnabled());
    }

    private boolean isEnabled() {
        AppConfig appConfig = LitePal.where("key = ?", "enhance_mode_enabled").findFirst(AppConfig.class);
        if (appConfig == null) {
            return true;
        }
        return "1".equals(appConfig.getValue());
    }

    private boolean isXposedActive() {
        return Util.isXposedActive();
    }
}

