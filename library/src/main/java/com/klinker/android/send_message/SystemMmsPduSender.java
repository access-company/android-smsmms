/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.klinker.android.send_message;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.text.TextUtils;

import com.android.mms.MmsConfig;
import com.android.mms.util.ExternalLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Posts a raw PDU to the MMSC through the system MMS service.
 *
 * The system resolves the MMSC and the route to it from the carrier configuration, so this works
 * even when the app cannot read the APN database itself. Since Android 11, reading the APN
 * database is restricted to system apps, which means a direct connection to the MMSC is not
 * always possible.
 *
 * Adapted from {@code com.android.mms.transaction.DownloadManager}, which does the same thing for
 * the download direction. The temporary file handling, the content {@link Uri} for
 * {@code MmsFileProvider}, the unique broadcast action, the {@code FLAG_IMMUTABLE} workaround and
 * the config overrides all come from there.
 *
 * This is a blocking call and must not be used from the main thread.
 */
class SystemMmsPduSender {

    private static final String ACTION_PREFIX =
            "com.klinker.android.send_message.SystemMmsPduSender.";

    private static final long SEND_TIMEOUT_MS = 30 * 1000;

    private SystemMmsPduSender() {
    }

    /**
     * @param locationUrl the URL to post the PDU to, or null to let the system use the MMSC of the
     *                    current carrier configuration.
     * @return true when the system reported that the PDU was sent.
     */
    static boolean send(Context context, byte[] pdu, int subscriptionId, String locationUrl) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            // SmsManager cannot send a PDU on its own before Lollipop.
            return false;
        }
        if (pdu == null || pdu.length == 0) {
            ExternalLogger.w("[SystemMmsPduSender] send() [end1] empty pdu");
            return false;
        }

        final Context applicationContext = context.getApplicationContext();
        final String fileName = "ack." + Math.abs(new Random().nextLong()) + ".dat";
        final File pduFile = new File(applicationContext.getCacheDir(), fileName);

        boolean written = false;
        FileOutputStream writer = null;
        try {
            writer = new FileOutputStream(pduFile);
            writer.write(pdu);
            written = true;
        } catch (IOException e) {
            ExternalLogger.e("[SystemMmsPduSender] send() [end2] failed to write the pdu", e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
            if (!written) {
                // The file can already exist even when the write failed part way through. Leaving
                // it behind would add another file to the cache on every retry.
                pduFile.delete();
            }
        }
        if (!written) {
            return false;
        }

        final Uri contentUri = (new Uri.Builder())
                .authority(applicationContext.getPackageName() + ".MmsFileProvider")
                .path(fileName)
                .scheme(ContentResolver.SCHEME_CONTENT)
                .build();

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger resultCode = new AtomicInteger(Integer.MIN_VALUE);
        final String action = ACTION_PREFIX + UUID.randomUUID().toString();
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                resultCode.set(getResultCode());
                latch.countDown();
            }
        };

        registerReceiver(applicationContext, receiver, action);
        try {
            // Use a unique action in order to avoid cancelling the result of another send.
            final Intent sent = new Intent(action);
            // Workaround for using PendingIntent.FLAG_IMMUTABLE until compileSdkVersion is updated
            // to 31. Actual value from:
            // https://android.googlesource.com/platform/frameworks/base.git/+/android-13.0.0_r18/core/java/android/app/PendingIntent.java#247
            final int flagImmutable = 1 << 26;
            @SuppressLint("WrongConstant")
            final PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    applicationContext, 0, sent,
                    PendingIntent.FLAG_CANCEL_CURRENT | flagImmutable); // The "sent" is an implicit intent, so it has to be immutable.

            final Bundle configOverrides = new Bundle();
            final String httpParams = MmsConfig.getHttpParams();
            if (!TextUtils.isEmpty(httpParams)) {
                configOverrides.putString(SmsManager.MMS_CONFIG_HTTP_PARAMS, httpParams);
            }

            ExternalLogger.i("[SystemMmsPduSender] send() call system method. size=" + pdu.length
                    + ", locationUrl=" + locationUrl);
            SmsManagerFactory.createSmsManager(subscriptionId).sendMultimediaMessage(
                    applicationContext, contentUri, locationUrl, configOverrides, pendingIntent);

            if (!latch.await(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                ExternalLogger.w("[SystemMmsPduSender] send() [end3] timed out");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ExternalLogger.w("[SystemMmsPduSender] send() [end4] interrupted", e);
            return false;
        } catch (Exception e) {
            // The system service can reject the PDU, for example when the device does not allow
            // sending a PDU that is not a M-Send.req.
            ExternalLogger.e("[SystemMmsPduSender] send() [end5] failed to call system method", e);
            return false;
        } finally {
            try {
                applicationContext.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
            pduFile.delete();
        }

        final boolean sent = resultCode.get() == Activity.RESULT_OK;
        ExternalLogger.i("[SystemMmsPduSender] send() [end] sent=" + sent
                + ", resultCode=" + resultCode.get());
        return sent;
    }

    private static void registerReceiver(Context context, BroadcastReceiver receiver, String action) {
        // If targetSdkVersion is 34, runtime-registered broadcast receivers must specify the
        // export behavior. The result is sent back by the system, so the receiver has to be
        // exported.
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
                registerReceiverMethod.invoke(context, receiver, new IntentFilter(action), receiverExported);
                return;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        context.registerReceiver(receiver, new IntentFilter(action));
    }
}
