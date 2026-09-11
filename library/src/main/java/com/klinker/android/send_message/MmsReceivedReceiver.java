/*
 * Copyright (C) 2015 Jacob Klinker
 *
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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.mms.service_alt.DownloadRequest;
import com.android.mms.service_alt.MmsConfig;
import com.android.mms.transaction.DownloadManager;
import com.android.mms.transaction.HttpUtils;
import com.android.mms.transaction.RetryScheduler;
import com.android.mms.transaction.TransactionSettings;
import com.android.mms.util.ExternalLogger;
import com.android.mms.util.SendingProgressTokenManager;
import com.google.android.mms.InvalidHeaderValueException;
import com.google.android.mms.MmsException;
import com.google.android.mms.pdu_alt.EncodedStringValue;
import com.google.android.mms.pdu_alt.GenericPdu;
import com.google.android.mms.pdu_alt.NotificationInd;
import com.google.android.mms.pdu_alt.NotifyRespInd;
import com.google.android.mms.pdu_alt.PduComposer;
import com.google.android.mms.pdu_alt.PduHeaders;
import com.google.android.mms.pdu_alt.PduParser;
import com.google.android.mms.pdu_alt.PduPersister;
import com.google.android.mms.pdu_alt.RetrieveConf;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.google.android.mms.pdu_alt.PduHeaders.STATUS_DEFERRED;
import static com.google.android.mms.pdu_alt.PduHeaders.STATUS_RETRIEVED;

public abstract class MmsReceivedReceiver extends BroadcastReceiver {
    private static final String TAG = "MmsReceivedReceiver";

    public static final String MMS_RECEIVED = "com.klinker.android.messaging.MMS_RECEIVED";
    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_LOCATION_URL = "location_url";
    public static final String EXTRA_TRIGGER_PUSH = "trigger_push";
    public static final String EXTRA_URI = "notification_ind_uri";
    public static final String SUBSCRIPTION_ID = "subscription_id";
    /** The result code the system reported for the download. */
    public static final String EXTRA_RESULT_CODE = "result_code";

    private static final String LOCATION_SELECTION =
            Telephony.Mms.MESSAGE_TYPE + "=? AND " + Telephony.Mms.CONTENT_LOCATION + " =?";

    private static final ExecutorService RECEIVE_NOTIFICATION_EXECUTOR = Executors.newSingleThreadExecutor();

    public abstract void onMessageReceived(Context context, Uri messageUri);
    public abstract void onError(Context context, String error);

    public MmscInformation getMmscInfoForReceptionAck(Context context) {
        // Override this and provide the MMSC to send the ACK to.
        // some carriers will download duplicate MMS messages without this ACK. When using the
        // system sending method, apparently Google does not do this for us. Not sure why.
        //
        // Returning null is fine: the ACK is then only sent through the system MMS service,
        // which resolves the MMSC from the carrier configuration. The MMSC returned here is used
        // as a fallback for the case the system refuses to send the PDU. Reading the APN database
        // is restricted to system apps since Android 11, so the MMSC is often unknown to the app.

        return null;
    }

    @Override
    public final void onReceive(final Context context, final Intent intent) {
        Log.v(TAG, "MMS has finished downloading, persisting it to the database");

        final Uri notificationUri = intent.getParcelableExtra(EXTRA_URI);
        final String uri = notificationUri != null ? notificationUri.toString() : "null";
        ExternalLogger.i("[MmsReceivedReceiver] onReceive() [start] uri=" + uri);

        final String path = intent.getStringExtra(EXTRA_FILE_PATH);
        final int subscriptionId = intent.getIntExtra(SUBSCRIPTION_ID, Utils.getDefaultSubscriptionId());
        Log.v(TAG, path);

        new Thread(new Runnable() {
            @Override
            public void run() {
                ExternalLogger.i("[MmsReceivedReceiver] onReceive() thread [start] uri=" + uri);
                FileInputStream reader = null;
                Uri messageUri = null;
                String errorMessage = null;

                try {
                    File mDownloadFile = new File(path);
                    final int nBytes = (int) mDownloadFile.length();
                    reader = new FileInputStream(mDownloadFile);
                    final byte[] response = new byte[nBytes];
                    reader.read(response, 0, nBytes);
                    ExternalLogger.d("[MmsReceivedReceiver] onReceive() thread file size=" + nBytes + ", path=" + path);

                    // The M-Notification.ind has to be read before it is deleted by persist().
                    Acknowledgement acknowledgement = prepareAcknowledgement(context, intent, response);

                    ExternalLogger.d("[MmsReceivedReceiver] onReceive() thread call persist");
                    messageUri = DownloadRequest.persist(context, response,
                            new MmsConfig.Overridden(new MmsConfig(context), null),
                            intent.getStringExtra(EXTRA_LOCATION_URL),
                            subscriptionId, null, notificationUri);
                    ExternalLogger.d("[MmsReceivedReceiver] onReceive() thread messageUri=" + messageUri);

                    Log.v(TAG, "response saved successfully");
                    Log.v(TAG, "response length: " + response.length);
                    mDownloadFile.delete();

                    if (acknowledgement != null) {
                        // Report the message as retrieved only when it was stored. Reporting a
                        // message the app failed to store lets the MMSC drop it for good.
                        Log.v(TAG, "running the common async notifier for download");
                        List<CommonAsyncTask> tasks = acknowledgement.getTasks(
                                context, subscriptionId, messageUri != null);
                        for (CommonAsyncTask task : tasks)
                            task.executeOnExecutor(RECEIVE_NOTIFICATION_EXECUTOR);
                    }

                    notifyReceiveCompleted(intent);
                } catch (FileNotFoundException e) {
                    errorMessage = "MMS received, file not found exception";
                    Log.e(TAG, errorMessage, e);
                    ExternalLogger.e("[MmsReceivedReceiver] onReceive() thread [FileNotFoundException]", e);
                } catch (IOException e) {
                    errorMessage = "MMS received, io exception";
                    Log.e(TAG, errorMessage, e);
                    ExternalLogger.e("[MmsReceivedReceiver] onReceive() thread [IOException]", e);
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (IOException e) {
                            errorMessage = "MMS received, io exception";
                            Log.e(TAG, "MMS received, io exception", e);
                        }
                    }
                }

                handleRetrieveFailure(context, intent, messageUri != null);
                DownloadManager.finishDownload(context, intent.getStringExtra(EXTRA_LOCATION_URL));
                if (messageUri != null) {
                    onMessageReceived(context, messageUri);
                }

                if (errorMessage != null) {
                    onError(context, errorMessage);
                }
                ExternalLogger.i("[MmsReceivedReceiver] onReceive() thread [end]");
            }
        }).start();
        ExternalLogger.i("[MmsReceivedReceiver] onReceive() [end]");
    }

    protected void notifyReceiveCompleted(Intent intent){}

    private static boolean isWifiActive(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);

        Network[] networks = connectivityManager.getAllNetworks();
        if (networks != null) {
            for (Network net: networks) {
                NetworkInfo info = connectivityManager.getNetworkInfo(net);
                if (info != null && ConnectivityManager.TYPE_WIFI == info.getType() && info.isConnected()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Counts a failed retrieval so that it is eventually given up on.
     *
     * The M-Notification.ind is kept when the message could not be stored, which makes it a
     * pending download again. Without counting the attempt it would be retried forever, and since
     * the pending downloads are processed one at a time, it would also hold up every other
     * message.
     *
     * @param stored Whether the downloaded message was stored.
     */
    private void handleRetrieveFailure(Context context, Intent intent, boolean stored) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return;
        }

        final Uri uri = intent.getParcelableExtra(EXTRA_URI);
        // The HTTP status is only reported for a HTTP failure, and the result PendingIntent of the
        // download is immutable, so the extra never actually arrives. The result code the system
        // reported is carried over by the download receiver instead, and is the only reliable way
        // to tell a failed download from a failed store.
        final int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_OK);
        final int httpError = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0);
        if (resultCode != Activity.RESULT_OK) {
            if (!Utils.isMmsOverWifiEnabled(context) && isWifiActive(context)) {
                // Sometimes MMS can not be acquired if Wifi is enabled.
                // For example, if you are playing Youtube in the foreground.
                // The failure is caused by the environment, so it is not counted as an attempt.
                // The due time is still put off, because leaving it alone makes this message be
                // retried at once and hold up every other pending message.
                ExternalLogger.w("[MmsReceivedReceiver] handleRetrieveFailure() the download failed while Wi-Fi is active, so it is not counted. resultCode="
                        + resultCode + ", uri=" + uri);
                RetryScheduler.getInstance(context).postponeRetry(uri);
                return;
            }
            ExternalLogger.w("[MmsReceivedReceiver] handleRetrieveFailure() schedule retry. the download failed. resultCode="
                    + resultCode + ", HTTP status=" + httpError + ", uri=" + uri);
            RetryScheduler.getInstance(context).scheduleRetry(uri);
            return;
        }

        if (!stored) {
            // The message was downloaded but could not be stored, which is not caused by the
            // environment. It is always counted as an attempt so that it is given up on.
            ExternalLogger.w("[MmsReceivedReceiver] handleRetrieveFailure() schedule retry. the message was not stored. uri=" + uri);
            RetryScheduler.getInstance(context).scheduleRetry(uri);
        }
    }

    private static NotificationInd getNotificationInd(Context context, Intent intent) throws MmsException {
        return (NotificationInd) PduPersister.getPduPersister(context).load((Uri) intent.getParcelableExtra(EXTRA_URI));
    }

    private static abstract class CommonAsyncTask extends AsyncTask<Void, Void, Void> {
        private static final int MAX_ACK_ATTEMPTS = 3;
        private static final long ACK_RETRY_INTERVAL_MS = 2 * 1000;

        protected final Context mContext;
        /** The settings of the MMSC, or null when the MMSC of the carrier is unknown. */
        protected final TransactionSettings mTransactionSettings;
        final NotificationInd mNotificationInd;
        final String mContentLocation;
        private final int mSubscriptionId;

        CommonAsyncTask(Context context, TransactionSettings settings, NotificationInd ind,
                        int subscriptionId) {
            mContext = context;
            mTransactionSettings = settings;
            mNotificationInd = ind;
            mContentLocation = new String(ind.getContentLocation());
            mSubscriptionId = subscriptionId;
        }

        /**
         * Reports the result of the retrieval to the MMSC.
         *
         * The MMSC keeps a message that has not been acknowledged and notifies the device of it
         * again, which makes the same message be downloaded more than once. The PDU is therefore
         * sent through the system MMS service first, because the MMSC of the carrier cannot be
         * resolved by the app itself since Android 11. A direct connection to the MMSC is only
         * used as a fallback, and only when the MMSC happens to be known.
         *
         * @param pdu A byte array which contains the data of the PDU.
         * @param name The name of the PDU, for logging.
         */
        void sendAcknowledgement(byte[] pdu, String name) {
            // Some carriers expect the report at the location the message was retrieved from
            // instead of the MMSC.
            final String locationUrl =
                    com.android.mms.MmsConfig.getNotifyWapMMSC() ? mContentLocation : null;

            for (int attempt = 1; attempt <= MAX_ACK_ATTEMPTS; attempt++) {
                if (SystemMmsPduSender.send(mContext, pdu, mSubscriptionId, locationUrl)) {
                    ExternalLogger.i("[CommonAsyncTask] sendAcknowledgement() sent " + name
                            + " through the system. attempt=" + attempt);
                    return;
                }

                if (mTransactionSettings != null
                        && !TextUtils.isEmpty(mTransactionSettings.getMmscUrl())) {
                    try {
                        sendPdu(pdu, locationUrl != null
                                ? locationUrl : mTransactionSettings.getMmscUrl());
                        ExternalLogger.i("[CommonAsyncTask] sendAcknowledgement() sent " + name
                                + " through a direct connection. attempt=" + attempt);
                        return;
                    } catch (IOException e) {
                        ExternalLogger.w("[CommonAsyncTask] sendAcknowledgement() failed to send "
                                + name + " through a direct connection. attempt=" + attempt, e);
                    } catch (MmsException e) {
                        ExternalLogger.w("[CommonAsyncTask] sendAcknowledgement() failed to send "
                                + name + " through a direct connection. attempt=" + attempt, e);
                    }
                }

                if (attempt < MAX_ACK_ATTEMPTS) {
                    try {
                        Thread.sleep(ACK_RETRY_INTERVAL_MS * attempt);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            // The message stays on the MMSC as if it had never been retrieved, so it can be
            // notified and downloaded again.
            ExternalLogger.e("[CommonAsyncTask] sendAcknowledgement() gave up sending " + name);
        }

        /**
         * A common method to send a PDU to MMSC.
         *
         * @param pdu A byte array which contains the data of the PDU.
         * @param mmscUrl Url of the recipient MMSC.
         * @return A byte array which contains the response data.
         *         If an HTTP error code is returned, an IOException will be thrown.
         * @throws java.io.IOException if any error occurred on network interface or
         *         an HTTP error code(>=400) returned from the server.
         * @throws com.google.android.mms.MmsException if pdu is null.
         */
        byte[] sendPdu(byte[] pdu, String mmscUrl) throws IOException, MmsException {
            return sendPdu(SendingProgressTokenManager.NO_TOKEN, pdu, mmscUrl);
        }

        /**
         * A common method to send a PDU to MMSC.
         *
         * @param token The token to identify the sending progress.
         * @param pdu A byte array which contains the data of the PDU.
         * @param mmscUrl Url of the recipient MMSC.
         * @return A byte array which contains the response data.
         *         If an HTTP error code is returned, an IOException will be thrown.
         * @throws java.io.IOException if any error occurred on network interface or
         *         an HTTP error code(>=400) returned from the server.
         * @throws com.google.android.mms.MmsException if pdu is null.
         */
        private byte[] sendPdu(final long token, final byte[] pdu,
                       final String mmscUrl) throws IOException, MmsException {
            if (pdu == null) {
                throw new MmsException();
            }

            if (mmscUrl == null) {
                throw new IOException("Cannot establish route: mmscUrl is null");
            }

            if (com.android.mms.transaction.Transaction.useWifi(mContext)) {
                return HttpUtils.httpConnection(
                        mContext, token,
                        mmscUrl,
                        pdu, HttpUtils.HTTP_POST_METHOD,
                        false, null, 0);
            }

            return Utils.ensureRouteToMmsNetwork(mContext, mmscUrl, mTransactionSettings.getProxyAddress(), new Utils.Task<byte[]>() {
                @Override
                public byte[] run() throws IOException {
                    return HttpUtils.httpConnection(
                            mContext, token,
                            mmscUrl,
                            pdu, HttpUtils.HTTP_POST_METHOD,
                            mTransactionSettings.isProxySet(),
                            mTransactionSettings.getProxyAddress(),
                            mTransactionSettings.getProxyPort());
                }
            });
        }
    }

    private static class NotifyRespTask extends CommonAsyncTask {
        private final int mStatus;

        NotifyRespTask(Context context, NotificationInd ind, TransactionSettings settings,
                       int subscriptionId, int status) {
            super(context, settings, ind, subscriptionId);
            mStatus = status;
        }

        @Override
        protected Void doInBackground(Void... params) {
            // Create the M-NotifyResp.ind
            NotifyRespInd notifyRespInd = null;
            try {
                notifyRespInd = new NotifyRespInd(
                        PduHeaders.CURRENT_MMS_VERSION,
                        mNotificationInd.getTransactionId(),
                        mStatus);

                // Pack M-NotifyResp.ind and send it
                sendAcknowledgement(new PduComposer(mContext, notifyRespInd).make(),
                        "M-NotifyResp.ind(status=" + mStatus + ")");
            } catch (MmsException e) {
                Log.e(TAG, "error", e);
                ExternalLogger.e("[NotifyRespTask] doInBackground() failed to build the pdu", e);
            }
            return null;
        }
    }

    private static class AcknowledgeIndTask extends CommonAsyncTask {
        private final RetrieveConf mRetrieveConf;

        AcknowledgeIndTask(Context context, NotificationInd ind, TransactionSettings settings,
                           RetrieveConf rc, int subscriptionId) {
            super(context, settings, ind, subscriptionId);
            mRetrieveConf = rc;
        }

        @Override
        protected Void doInBackground(Void... params) {
            // Send M-Acknowledge.ind to MMSC if required.
            // If the Transaction-ID isn't set in the M-Retrieve.conf, it means
            // the MMS proxy-relay doesn't require an ACK.
            byte[] tranId = mRetrieveConf.getTransactionId();
            if (tranId != null) {
                Log.v(TAG, "sending ACK to the MMSC");
                // Create M-Acknowledge.ind
                com.google.android.mms.pdu_alt.AcknowledgeInd acknowledgeInd = null;

                try {
                    acknowledgeInd = new com.google.android.mms.pdu_alt.AcknowledgeInd(
                            PduHeaders.CURRENT_MMS_VERSION, tranId);

                    // insert the 'from' address per spec
                    String lineNumber = Utils.getMyPhoneNumber(mContext);

                    if (lineNumber != null) {
                        acknowledgeInd.setFrom(new EncodedStringValue(lineNumber));
                    } else {
                        acknowledgeInd.setFrom(new EncodedStringValue(""));
                    }

                    // Pack M-Acknowledge.ind and send it
                    sendAcknowledgement(new PduComposer(mContext, acknowledgeInd).make(),
                            "M-Acknowledge.ind");
                } catch (InvalidHeaderValueException e) {
                    Log.e(TAG, "error", e);
                    ExternalLogger.e("[AcknowledgeIndTask] doInBackground() failed to build the pdu", e);
                }
            }
            return null;
        }
    }

    /**
     * Everything that is needed to report the result of the retrieval to the MMSC.
     *
     * It has to be collected before the downloaded message is stored, because storing it deletes
     * the M-Notification.ind the report is built from.
     */
    private static class Acknowledgement {
        private final NotificationInd mNotificationInd;
        private final RetrieveConf mRetrieveConf;
        private final TransactionSettings mTransactionSettings;

        Acknowledgement(NotificationInd notificationInd, RetrieveConf retrieveConf,
                        TransactionSettings transactionSettings) {
            mNotificationInd = notificationInd;
            mRetrieveConf = retrieveConf;
            mTransactionSettings = transactionSettings;
        }

        /**
         * @param retrieved Whether the message was stored. When it was not, the message is
         *                  reported as deferred so that the MMSC keeps it.
         */
        List<CommonAsyncTask> getTasks(Context context, int subscriptionId, boolean retrieved) {
            final List<CommonAsyncTask> tasks = new ArrayList<>();
            if (retrieved) {
                tasks.add(new AcknowledgeIndTask(context, mNotificationInd, mTransactionSettings,
                        mRetrieveConf, subscriptionId));
                tasks.add(new NotifyRespTask(context, mNotificationInd, mTransactionSettings,
                        subscriptionId, STATUS_RETRIEVED));
            } else {
                ExternalLogger.w("[Acknowledgement] getTasks() the message was not stored, report it as deferred");
                tasks.add(new NotifyRespTask(context, mNotificationInd, mTransactionSettings,
                        subscriptionId, STATUS_DEFERRED));
            }
            return tasks;
        }
    }

    private Acknowledgement prepareAcknowledgement(Context context, Intent intent, byte[] response) {
        if (response.length == 0) {
            Log.v(TAG, "MmsReceivedReceiver.sendNotification blank response");
            ExternalLogger.w("[MmsReceivedReceiver] prepareAcknowledgement() [end1] blank response");
            return null;
        }

        final GenericPdu pdu =
                (new PduParser(response, new MmsConfig.Overridden(new MmsConfig(context), null).
                        getSupportMmsContentDisposition())).parse();
        ExternalLogger.d("[MmsReceivedReceiver] prepareAcknowledgement() pdu=" + ExternalLogger.getNameWithHash(pdu));
        if (!(pdu instanceof RetrieveConf)) {
            android.util.Log.e(TAG, "MmsReceivedReceiver.sendNotification failed to parse pdu");
            ExternalLogger.w("[MmsReceivedReceiver] prepareAcknowledgement() [end2] failed to parse pdu");
            return null;
        }

        try {
            final NotificationInd ind = getNotificationInd(context, intent);
            final MmscInformation mmsc = getMmscInfoForReceptionAck(context);
            // The MMSC is only needed for the fallback, so the report is still worth sending
            // without it. The system MMS service resolves the MMSC on its own.
            final TransactionSettings transactionSettings = mmsc == null
                    ? null : new TransactionSettings(mmsc.mmscUrl, mmsc.mmsProxy, mmsc.proxyPort);
            if (mmsc == null) {
                Log.v(TAG, "No MMSC information set, so the acknowledgement can only be sent through the system");
                ExternalLogger.w("[MmsReceivedReceiver] prepareAcknowledgement() No MMSC information set, so the acknowledgement can only be sent through the system");
            }

            ExternalLogger.i("[MmsReceivedReceiver] prepareAcknowledgement() [end] success");
            return new Acknowledgement(ind, (RetrieveConf) pdu, transactionSettings);
        } catch (MmsException e) {
            Log.e(TAG, "error", e);
            ExternalLogger.e("[MmsReceivedReceiver] prepareAcknowledgement() [exception]", e);
            return null;
        }
    }

    public static class MmscInformation {
        String mmscUrl;
        String mmsProxy;
        int proxyPort;

        public MmscInformation(String mmscUrl, String mmsProxy, int proxyPort) {
            this.mmscUrl = mmscUrl;
            this.mmsProxy = mmsProxy;
            this.proxyPort = proxyPort;
        }
    }
}
