package com.klinker.android.send_message;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.access_company.android.mms.MmsLogger;

public abstract class StatusUpdatedReceiver extends BroadcastReceiver {

    // Updates the status of the message in the internal database
    public abstract void updateInInternalDatabase(Context context, Intent intent, int receiverResultCode);

    // allows the implementer to update the status of the message in their database
    public abstract void onMessageStatusUpdated(Context context, Intent intent, int receiverResultCode);

    @Override
    public final void onReceive(final Context context, final Intent intent) {
        final int resultCode = getResultCode();
        MmsLogger.d("StatusUpdatedReceiver#onReceive() current=" + getClass() + ", resultCode=" + resultCode);
        new Thread(new Runnable() {
            @Override
            public void run() {
                MmsLogger.d("StatusUpdatedReceiver#onReceive() run() current=" + getClass() + ", resultCode=" + resultCode);
                onMessageStatusUpdated(context, intent, resultCode);
                updateInInternalDatabase(context, intent, resultCode);
            }
        }).start();
    }

}
