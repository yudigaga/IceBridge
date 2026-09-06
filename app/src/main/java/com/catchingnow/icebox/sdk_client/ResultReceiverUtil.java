package com.catchingnow.icebox.sdk_client;

import android.os.Parcel;
import android.os.ResultReceiver;

final class ResultReceiverUtil {
    private ResultReceiverUtil() {
    }

    static ResultReceiver receiverForSending(ResultReceiver actualReceiver) {
        Parcel parcel = Parcel.obtain();
        try {
            actualReceiver.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return ResultReceiver.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }
}
