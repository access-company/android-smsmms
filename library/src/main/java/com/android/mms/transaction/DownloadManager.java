
package com.android.mms.transaction;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SqliteWrapper;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.text.TextUtils;

import com.android.mms.MmsConfig;
import com.android.mms.util.ExternalLogger;
import com.klinker.android.logger.Log;
import com.klinker.android.send_message.BroadcastUtils;
import com.klinker.android.send_message.MmsReceivedReceiver;
import com.klinker.android.send_message.SmsManagerFactory;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In order to avoid downloading duplicate MMS.
 * We should manage to call SMSManager.downloadMultimediaMessage().
 */
public class DownloadManager {
    private static final String TAG = "DownloadManager";
    private static DownloadManager ourInstance = new DownloadManager();
    private static final ConcurrentHashMap<String, MmsDownloadReceiver> mMap = new ConcurrentHashMap<>();
    private static final AtomicInteger sMaxConnection = new AtomicInteger(5);

    public static DownloadManager getInstance() {
        return ourInstance;
    }

    private DownloadManager() {

    }

    public void downloadMultimediaMessage(final Context context, final String location, Uri uri, boolean byPush, int subscriptionId) {
        ExternalLogger.i("[DownloadManager] downloadMultimediaMessage() [start] uri=" + uri + ", location=" + location + ", byPush=" + byPush);
        if (location == null || mMap.get(location) != null || mMap.size() >= sMaxConnection.get()) {
            ExternalLogger.d("[DownloadManager] downloadMultimediaMessage() [end1]");
            return;
        }

        // TransactionService can keep uri and location in memory while SmsManager download Mms.
        if (!isNotificationExist(context, location)) {
            ExternalLogger.d("[DownloadManager] downloadMultimediaMessage() [end2]");
            return;
        }

        MmsDownloadReceiver receiver = new MmsDownloadReceiver();
        mMap.put(location, receiver);

        // Use unique action in order to avoid cancellation of notifying download result.
        // If targetSdkVersion is 34, Runtime-registered broadcasts receivers must specify export behavior
        try {
            int tiramisuApiLevel = (Integer) Build.VERSION_CODES.class.getField("TIRAMISU").get(null);
            int receiverExported = (Integer) Context.class.getField("RECEIVER_EXPORTED").get(null);

            if (Build.VERSION.SDK_INT >= tiramisuApiLevel) {
                Method registerReceiverMethod = Context.class.getMethod(
                        "registerReceiver",
                        BroadcastReceiver.class,
                        IntentFilter.class,
                        int.class
                );
                registerReceiverMethod.invoke(
                        context.getApplicationContext(),
                        receiver,
                        new IntentFilter(receiver.mAction),
                        receiverExported
                );
            } else {
                context.getApplicationContext().registerReceiver(receiver, new IntentFilter(receiver.mAction));
            }
        } catch (ReflectiveOperationException e) {
            context.getApplicationContext().registerReceiver(receiver, new IntentFilter(receiver.mAction));
        }

        Log.v(TAG, "receiving with system method");
        final String fileName = "download." + String.valueOf(Math.abs(new Random().nextLong())) + ".dat";
        File mDownloadFile = new File(context.getCacheDir(), fileName);
        Uri contentUri = (new Uri.Builder())
                .authority(context.getPackageName() + ".MmsFileProvider")
                .path(fileName)
                .scheme(ContentResolver.SCHEME_CONTENT)
                .build();
        Intent download = new Intent(receiver.mAction);
        download.putExtra(MmsReceivedReceiver.EXTRA_FILE_PATH, mDownloadFile.getPath());
        download.putExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL, location);
        download.putExtra(MmsReceivedReceiver.EXTRA_TRIGGER_PUSH, byPush);
        download.putExtra(MmsReceivedReceiver.EXTRA_URI, uri);
        download.putExtra(MmsReceivedReceiver.SUBSCRIPTION_ID, subscriptionId);
        // Workaround for using PendingIntent.FLAG_MUTABLE until compileSdkVersion is updated to 31.
        // Actual value from:
        // https://android.googlesource.com/platform/frameworks/base.git/+/android-13.0.0_r18/core/java/android/app/PendingIntent.java#262
        final int flagMutable = 1<<25;
        @SuppressLint("WrongConstant")
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 0, download, PendingIntent.FLAG_CANCEL_CURRENT | flagMutable);

        final SmsManager smsManager = SmsManagerFactory.createSmsManager(subscriptionId);

        Bundle configOverrides = new Bundle();
        String httpParams = MmsConfig.getHttpParams();
        if (!TextUtils.isEmpty(httpParams)) {
            configOverrides.putString(SmsManager.MMS_CONFIG_HTTP_PARAMS, httpParams);
        } else {
            // this doesn't seem to always work...
            // configOverrides = smsManager.getCarrierConfigValues();
        }

        ExternalLogger.d("[DownloadManager] downloadMultimediaMessage() call system method. path=" + mDownloadFile.getPath());
        grantUriPermission(context, contentUri);
        smsManager.downloadMultimediaMessage(context, location, contentUri, configOverrides, pendingIntent);
        ExternalLogger.i("[DownloadManager] downloadMultimediaMessage() [end3]");
    }

    private void grantUriPermission(Context context, Uri contentUri) {
        context.grantUriPermission(context.getPackageName() + ".MmsFileProvider",
                contentUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    private static class MmsDownloadReceiver extends BroadcastReceiver {
        private static final String ACTION_PREFIX = "com.android.mms.transaction.DownloadManager$MmsDownloadReceiver.";
        private final String mAction;

        MmsDownloadReceiver() {
            mAction = ACTION_PREFIX + UUID.randomUUID().toString();
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            ExternalLogger.i("[MmsDownloadReceiver] onReceive() [start] uri=" + intent.getParcelableExtra(MmsReceivedReceiver.EXTRA_URI));
            context.unregisterReceiver(this);

            PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MMS DownloadReceiver");
            wakeLock.acquire(60 * 1000);

            Intent newIntent = (Intent) intent.clone();
            newIntent.setAction(MmsReceivedReceiver.MMS_RECEIVED);
            BroadcastUtils.sendExplicitBroadcast(context, newIntent, MmsReceivedReceiver.MMS_RECEIVED);
            ExternalLogger.i("[MmsDownloadReceiver] onReceive() [end]");
        }
    }

    public static void finishDownload(String location) {
        ExternalLogger.d("[MmsDownloadReceiver] finishDownload() location=" + location);
        if (location != null) {
            mMap.remove(location);
        }
    }

    private static boolean isNotificationExist(Context context, String location) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return true;
        }

        String selection = Telephony.Mms.CONTENT_LOCATION + " = ?";
        String[] selectionArgs = new String[] { location };
        Cursor c = SqliteWrapper.query(
                context, context.getContentResolver(),
                Telephony.Mms.CONTENT_URI, new String[] { Telephony.Mms._ID },
                selection, selectionArgs, null);
        if (c != null) {
            try {
                if (c.getCount() > 0) {
                    return true;
                }
            } finally {
                c.close();
            }
        }

        return false;
    }

    public static void setMaxConnection(int max) {
        sMaxConnection.set(max);
    }
}
