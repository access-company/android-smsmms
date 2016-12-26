/*
 * Copyright 2014 Jacob Klinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mms.transaction;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SqliteWrapper;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.provider.Telephony.Mms;

import com.android.mms.LogTag;
import com.android.mms.MmsConfig;
import com.google.android.mms.ContentType;
import com.google.android.mms.MmsException;
import com.klinker.android.logger.Log;

import static android.provider.Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION;
import static android.provider.Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION;

/**
 * Receives Intent.WAP_PUSH_RECEIVED_ACTION intents and starts the
 * TransactionService by passing the push-data to it.
 */
public class PushReceiver extends BroadcastReceiver {
    private static final String TAG = LogTag.TAG;
    private static final boolean LOCAL_LOGV = true;

    static final String[] PROJECTION = new String[] {
            Mms.CONTENT_LOCATION,
            Mms.LOCKED
    };

    static final int COLUMN_CONTENT_LOCATION      = 0;

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.v(TAG, intent.getAction() + " " + intent.getType());
        if ((intent.getAction().equals(WAP_PUSH_DELIVER_ACTION) || intent.getAction().equals(WAP_PUSH_RECEIVED_ACTION))
                && ContentType.MMS_MESSAGE.equals(intent.getType())) {
            if (LOCAL_LOGV) {
                Log.v(TAG, "Received PUSH Intent: " + intent);
            }

            SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
            if ((!sharedPrefs.getBoolean("receive_with_stock", false) && Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT && sharedPrefs.getBoolean("override", true))
                    || Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                MmsConfig.init(context);
                PushReceiverService.startService(context, intent);

                Log.v("mms_receiver", context.getPackageName() + " received and aborted");

                abortBroadcast();
            } else {
                clearAbortBroadcast();
                Intent notificationBroadcast = new Intent(com.klinker.android.send_message.Transaction.NOTIFY_OF_MMS);
                notificationBroadcast.putExtra("receive_through_stock", true);
                context.sendBroadcast(notificationBroadcast);

                Log.v("mms_receiver", context.getPackageName() + " received and not aborted");
            }
        }
    }

    public static String getContentLocation(Context context, Uri uri)
            throws MmsException {
        Cursor cursor = SqliteWrapper.query(context, context.getContentResolver(),
                uri, PROJECTION, null, null, null);

        if (cursor != null) {
            try {
                if ((cursor.getCount() == 1) && cursor.moveToFirst()) {
                    String location = cursor.getString(COLUMN_CONTENT_LOCATION);
                    cursor.close();
                    return location;
                }
            } finally {
                cursor.close();
            }
        }

        throw new MmsException("Cannot get X-Mms-Content-Location from: " + uri);
    }
}
