
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
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    /**
     * The downloads that could not be started because the maximum number of them was already in
     * flight.
     *
     * A download that is never started reports no result, so nothing counts it as an attempt and
     * nothing schedules it again. The only thing that would pick the message up is the pending
     * message it was notified by, which is processed one at a time and can be far behind. The
     * downloads are therefore kept here and started as soon as a connection is free.
     */
    private static final Queue<DeferredDownload> sDeferred = new ConcurrentLinkedQueue<>();

    /**
     * An upper bound on {@link #sDeferred}, so that a burst of notifications cannot make it grow
     * without end. The pending message of a download that is dropped is still there, so the
     * message is not lost.
     */
    private static final int MAX_DEFERRED_DOWNLOADS = 64;

    /**
     * The lock that guards the decision to start, to skip or to defer a download.
     *
     * Deciding it reads {@link #mMap} and {@link #sDeferred} and then writes to one of them, and
     * the callers run on more than one thread: a notification that has just arrived, the pending
     * message the transaction service picked, and a download that has finished. Each collection
     * is thread-safe on its own, but the decision as a whole has to be, too.
     */
    private static final Object sLock = new Object();

    public static DownloadManager getInstance() {
        return ourInstance;
    }

    private DownloadManager() {

    }

    /**
     * Asks the system to download a multimedia message.
     *
     * @return Whether the download was handed over to the system.
     */
    public boolean downloadMultimediaMessage(final Context context, final String location, Uri uri, boolean byPush, int subscriptionId) {
        ExternalLogger.i("[DownloadManager] downloadMultimediaMessage() [start] uri=" + uri + ", location=" + location + ", byPush=" + byPush);
        if (location == null) {
            ExternalLogger.d("[DownloadManager] downloadMultimediaMessage() [end1-1] no content location");
            return false;
        }

        // TransactionService can keep uri and location in memory while SmsManager download Mms.
        // This is checked before a connection is taken, so that a message that is not there any
        // more is never deferred and no connection has to be given back.
        if (!isNotificationExist(context, location)) {
            ExternalLogger.d("[DownloadManager] downloadMultimediaMessage() [end2]");
            return false;
        }

        final MmsDownloadReceiver receiver;
        synchronized (sLock) {
            if (mMap.get(location) != null) {
                // The same message is already being downloaded, which happens when the MMSC
                // notifies the device of it more than once.
                ExternalLogger.d("[DownloadManager] downloadMultimediaMessage() [end1-2] already downloading");
                return false;
            }
            if (mMap.size() >= sMaxConnection.get()) {
                deferDownload(location, uri, byPush, subscriptionId);
                ExternalLogger.i("[DownloadManager] downloadMultimediaMessage() [end1-3] too many downloads are in flight. deferred="
                        + sDeferred.size());
                return false;
            }
            // The connection is taken here, so that the checks above cannot interleave with
            // another caller.
            receiver = new MmsDownloadReceiver();
            mMap.put(location, receiver);
        }

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
        // Workaround for using PendingIntent.FLAG_IMMUTABLE until compileSdkVersion is updated to 31.
        // Actual value from:
        // https://android.googlesource.com/platform/frameworks/base.git/+/android-13.0.0_r18/core/java/android/app/PendingIntent.java#247
        final int flagImmutable = 1 << 26;
        @SuppressLint("WrongConstant")
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 0, download, PendingIntent.FLAG_CANCEL_CURRENT | flagImmutable); // Set the FLAG_IMMUTABLE because the "download" is an implicit intent.

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
        return true;
    }

    /**
     * Keeps a download that could not be started, so that it can be started later.
     *
     * Looking for a duplicate, checking the bound and adding the entry have to happen together,
     * or two callers can both add the same location or both find room at the bound.
     */
    private static void deferDownload(String location, Uri uri, boolean byPush, int subscriptionId) {
        synchronized (sLock) {
            for (DeferredDownload deferred : sDeferred) {
                if (location.equals(deferred.mLocation)) {
                    return;
                }
            }
            if (sDeferred.size() >= MAX_DEFERRED_DOWNLOADS) {
                ExternalLogger.w("[DownloadManager] deferDownload() too many deferred downloads. location=" + location);
                return;
            }
            sDeferred.add(new DeferredDownload(location, uri, byPush, subscriptionId));
        }
    }

    /**
     * Starts the download that has been waiting the longest, if there is one.
     *
     * An entry whose message is not in the mms provider any more, or whose message is already
     * being downloaded, cannot be started and is dropped. Waiting for the next download to finish
     * before the entries behind it are looked at would strand them, because the download that
     * just finished may have been the last one, so they are tried until one of them starts.
     */
    private static void startDeferredDownload(Context context) {
        while (!sDeferred.isEmpty() && mMap.size() < sMaxConnection.get()) {
            final DeferredDownload deferred;
            synchronized (sLock) {
                deferred = sDeferred.poll();
            }
            if (deferred == null) {
                return;
            }
            ExternalLogger.i("[DownloadManager] startDeferredDownload() uri=" + deferred.mUri
                    + ", deferred=" + sDeferred.size());
            if (getInstance().downloadMultimediaMessage(context, deferred.mLocation, deferred.mUri,
                    deferred.mByPush, deferred.mSubscriptionId)) {
                return;
            }
            // An entry that is only waiting for a free connection is deferred again by the call
            // above, and the condition of this loop then ends it.
        }
    }

    private static class DeferredDownload {
        private final String mLocation;
        private final Uri mUri;
        private final boolean mByPush;
        private final int mSubscriptionId;

        DeferredDownload(String location, Uri uri, boolean byPush, int subscriptionId) {
            mLocation = location;
            mUri = uri;
            mByPush = byPush;
            mSubscriptionId = subscriptionId;
        }
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
            // The result code is not part of the intent, so it has to be carried over explicitly.
            newIntent.putExtra(MmsReceivedReceiver.EXTRA_RESULT_CODE, getResultCode());
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

    /**
     * Reports that a download has finished and starts the next one that is waiting.
     *
     * @param context A context, to start the next download with.
     * @param location The content location of the download that has finished.
     */
    public static void finishDownload(Context context, String location) {
        finishDownload(location);
        startDeferredDownload(context);
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
