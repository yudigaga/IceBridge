package com.catchingnow.icebox.sdk_client;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

public final class IceBoxDiagnostic {
    public enum State {
        WORKING,
        NOT_INSTALLED,
        TOO_OLD,
        MODE_NOT_AVAILABLE,
        PROVIDER_UNAVAILABLE,
        PROVIDER_LAUNCH_BLOCKED,
        INVALID_RESPONSE,
        SECURITY_ERROR,
        QUERY_ERROR
    }

    private final State state;
    private final IceBox.WorkMode workMode;
    private final String detail;
    private final int versionCode;

    IceBoxDiagnostic(State state, IceBox.WorkMode workMode, String detail, int versionCode) {
        this.state = state;
        this.workMode = workMode;
        this.detail = detail;
        this.versionCode = versionCode;
    }

    public State getState() {
        return state;
    }

    public IceBox.WorkMode getWorkMode() {
        return workMode;
    }

    public String getDetail() {
        return detail;
    }

    public int getVersionCode() {
        return versionCode;
    }

    static IceBoxDiagnostic inspect(Context context) {
        final int versionCode;
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(IceBox.PACKAGE_NAME, 0);
            versionCode = packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException error) {
            return new IceBoxDiagnostic(State.NOT_INSTALLED, IceBox.WorkMode.MODE_NOT_AVAILABLE, "IceBox package is not installed", 0);
        }
        if (versionCode < IceBox.AVAILABLE_VERSION_CODE) {
            return new IceBoxDiagnostic(State.TOO_OLD, IceBox.WorkMode.MODE_NOT_AVAILABLE, "IceBox version is below the supported SDK version", versionCode);
        }

        Bundle extras = SdkImplement.authorizeExtras(context);
        try {
            Bundle result = context.getContentResolver().call(SdkImplement.getStateUri(), "query_mode", null, extras);
            if (result == null) {
                return new IceBoxDiagnostic(State.PROVIDER_UNAVAILABLE, IceBox.WorkMode.MODE_NOT_AVAILABLE, "IceBox STATE provider returned no response", versionCode);
            }
            String modeName = result.getString("work_mode");
            if (modeName == null) {
                return new IceBoxDiagnostic(State.INVALID_RESPONSE, IceBox.WorkMode.MODE_NOT_AVAILABLE, "IceBox STATE provider did not return work_mode", versionCode);
            }
            IceBox.WorkMode workMode;
            try {
                workMode = IceBox.WorkMode.valueOf(modeName);
            } catch (IllegalArgumentException error) {
                return new IceBoxDiagnostic(State.INVALID_RESPONSE, IceBox.WorkMode.MODE_NOT_AVAILABLE, "Unsupported IceBox work_mode: " + trim(modeName), versionCode);
            }
            if (workMode == IceBox.WorkMode.MODE_NOT_AVAILABLE) {
                return new IceBoxDiagnostic(State.MODE_NOT_AVAILABLE, workMode, "IceBox reported MODE_NOT_AVAILABLE", versionCode);
            }
            return new IceBoxDiagnostic(State.WORKING, workMode, "IceBox STATE provider responded normally", versionCode);
        } catch (SecurityException error) {
            return new IceBoxDiagnostic(State.SECURITY_ERROR, IceBox.WorkMode.MODE_NOT_AVAILABLE, trim(error.getMessage()), versionCode);
        } catch (RuntimeException error) {
            String detail = error.getClass().getSimpleName() + ": " + trim(error.getMessage());
            State state = detail.contains("Failed to find provider info") || detail.contains("Unable to launch app")
                    ? State.PROVIDER_LAUNCH_BLOCKED
                    : State.QUERY_ERROR;
            return new IceBoxDiagnostic(state, IceBox.WorkMode.MODE_NOT_AVAILABLE, detail, versionCode);
        }
    }

    private static String trim(String value) {
        if (value == null || value.isEmpty()) {
            return "No additional detail";
        }
        return value.length() > 160 ? value.substring(0, 160) : value;
    }
}
