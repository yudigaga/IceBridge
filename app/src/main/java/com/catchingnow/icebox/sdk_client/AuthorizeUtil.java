package com.catchingnow.icebox.sdk_client;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class AuthorizeUtil {
    private static PendingIntent authorizePendingIntent;

    private AuthorizeUtil() {
    }

    static synchronized PendingIntent getAuthorizedPI(Context context) {
        if (authorizePendingIntent == null) {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_IMMUTABLE
                    : 0;
            authorizePendingIntent = PendingIntent.getBroadcast(
                    context,
                    0x333,
                    new Intent(context, StateReceiver.class),
                    flags
            );
        }
        return authorizePendingIntent;
    }
}
