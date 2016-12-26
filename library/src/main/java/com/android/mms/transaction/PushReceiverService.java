package com.android.mms.transaction;

import android.app.IntentService;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SqliteWrapper;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Telephony;

import com.android.mms.LogTag;
import com.android.mms.MmsConfig;
import com.android.mms.service_alt.DownloadRequest;
import com.android.mms.service_alt.MmsNetworkManager;
import com.android.mms.service_alt.MmsRequestManager;
import com.google.android.mms.MmsException;
import com.google.android.mms.pdu_alt.DeliveryInd;
import com.google.android.mms.pdu_alt.GenericPdu;
import com.google.android.mms.pdu_alt.NotificationInd;
import com.google.android.mms.pdu_alt.PduHeaders;
import com.google.android.mms.pdu_alt.PduParser;
import com.google.android.mms.pdu_alt.PduPersister;
import com.google.android.mms.pdu_alt.ReadOrigInd;
import com.klinker.android.logger.Log;
import com.klinker.android.send_message.Utils;

import java.util.HashSet;
import java.util.Set;

import static com.google.android.mms.pdu_alt.PduHeaders.MESSAGE_TYPE_DELIVERY_IND;
import static com.google.android.mms.pdu_alt.PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND;
import static com.google.android.mms.pdu_alt.PduHeaders.MESSAGE_TYPE_READ_ORIG_IND;

public class PushReceiverService extends IntentService {
    private static final String TAG = LogTag.TAG;
    private static final boolean DEBUG = false;
    private static final boolean LOCAL_LOGV = true;

    private static Set<String> downloadedUrls = new HashSet<String>();

    public PushReceiverService() {
        super("PushReceiverService");
    }

    static void startService(Context context, Intent intent) {
        acquireWakeLock(context);

        Intent newIntent = (Intent) intent.clone();
        newIntent.setClass(context, PushReceiverService.class);
        context.startService(newIntent);
    }

    private static void acquireWakeLock(Context context) {
        PowerManager mgr = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

        PowerManager.WakeLock wakeLock = mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PushReceiverService");
        wakeLock.acquire(60 * 1000);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        handleIntent(intent);
    }
    
    private void handleIntent(Intent intent) {
        Log.v(TAG, "receiving a new mms message");

        // Get raw PDU push-data from the message and parse it
        byte[] pushData = intent.getByteArrayExtra("data");
        PduParser parser = new PduParser(pushData);
        GenericPdu pdu = parser.parse();

        if (null == pdu) {
            Log.e(TAG, "Invalid PUSH data");
            return;
        }

        PduPersister p = PduPersister.getPduPersister(this);
        ContentResolver cr = this.getContentResolver();
        int type = pdu.getMessageType();
        long threadId = -1;

        try {
            switch (type) {
                case MESSAGE_TYPE_DELIVERY_IND:
                case MESSAGE_TYPE_READ_ORIG_IND: {
                    threadId = findThreadId(this, pdu, type);
                    if (threadId == -1) {
                        // The associated SendReq isn't found, therefore skip
                        // processing this PDU.
                        break;
                    }

                    boolean group;

                    try {
                        group = com.klinker.android.send_message.Transaction.settings.getGroup();
                    } catch (Exception e) {
                        group = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("group_message", true);
                    }

                    Uri uri = p.persist(pdu, Uri.parse("content://mms/inbox"), true,
                            group, null);
                    // Update thread ID for ReadOrigInd & DeliveryInd.
                    ContentValues values = new ContentValues(1);
                    values.put(Telephony.Mms.THREAD_ID, threadId);
                    SqliteWrapper.update(this, cr, uri, values, null, null);
                    break;
                }
                case MESSAGE_TYPE_NOTIFICATION_IND: {
                    NotificationInd nInd = (NotificationInd) pdu;

                    if (MmsConfig.getTransIdEnabled()) {
                        byte [] contentLocation = nInd.getContentLocation();
                        if ('=' == contentLocation[contentLocation.length - 1]) {
                            byte [] transactionId = nInd.getTransactionId();
                            byte [] contentLocationWithId = new byte [contentLocation.length
                                    + transactionId.length];
                            System.arraycopy(contentLocation, 0, contentLocationWithId,
                                    0, contentLocation.length);
                            System.arraycopy(transactionId, 0, contentLocationWithId,
                                    contentLocation.length, transactionId.length);
                            nInd.setContentLocation(contentLocationWithId);
                        }
                    }

                    if (!isDuplicateNotification(this, nInd)) {
                        // Save the pdu. If we can start downloading the real pdu immediately,
                        // don't allow persist() to create a thread for the notificationInd
                        // because it causes UI jank.
                        boolean group;

                        try {
                            group = com.klinker.android.send_message.Transaction.settings.getGroup();
                        } catch (Exception e) {
                            group = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("group_message", true);
                        }

                        Uri uri = p.persist(pdu, Telephony.Mms.Inbox.CONTENT_URI,
                                !NotificationTransaction.allowAutoDownload(this),
                                group,
                                null);

                        String location = PushReceiver.getContentLocation(this, uri);
                        if (downloadedUrls.contains(location)) {
                            Log.v(TAG, "already added this download, don't download again");
                            return;
                        } else {
                            downloadedUrls.add(location);
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            Log.v(TAG, "receiving on a lollipop+ device");
                            boolean useSystem = true;

                            if (com.klinker.android.send_message.Transaction.settings != null) {
                                useSystem = com.klinker.android.send_message.Transaction.settings
                                        .getUseSystemSending();
                            } else {
                                useSystem = PreferenceManager.getDefaultSharedPreferences(this)
                                        .getBoolean("system_mms_sending", useSystem);
                            }

                            if (useSystem) {
                                DownloadManager.getInstance().downloadMultimediaMessage(this, location, uri, true);
                            } else {
                                Log.v(TAG, "receiving with lollipop method");
                                MmsRequestManager requestManager = new MmsRequestManager(this);
                                DownloadRequest request = new DownloadRequest(requestManager,
                                        Utils.getDefaultSubscriptionId(),
                                        location, uri, null, null,
                                        null, this);
                                MmsNetworkManager manager = new MmsNetworkManager(this, Utils.getDefaultSubscriptionId());
                                request.execute(this, manager);
                            }
                        } else {
                            if (NotificationTransaction.allowAutoDownload(this)) {
                                // Start service to finish the notification transaction.
                                Intent svc = new Intent(this, TransactionService.class);
                                svc.putExtra(TransactionBundle.URI, uri.toString());
                                svc.putExtra(TransactionBundle.TRANSACTION_TYPE,
                                        Transaction.NOTIFICATION_TRANSACTION);
                                svc.putExtra(TransactionBundle.LOLLIPOP_RECEIVING,
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP);
                                this.startService(svc);
                            } else {
                                Intent notificationBroadcast = new Intent(com.klinker.android.send_message.Transaction.NOTIFY_OF_MMS);
                                notificationBroadcast.putExtra("receive_through_stock", true);
                                this.sendBroadcast(notificationBroadcast);
                            }
                        }
                    } else if (LOCAL_LOGV) {
                        Log.v(TAG, "Skip downloading duplicate message: "
                                + new String(nInd.getContentLocation()));
                    }
                    break;
                }
                default:
                    Log.e(TAG, "Received unrecognized PDU.");
            }
        } catch (MmsException e) {
            Log.e(TAG, "Failed to save the data from PUSH: type=" + type, e);
        } catch (RuntimeException e) {
            Log.e(TAG, "Unexpected RuntimeException.", e);
        }

        if (LOCAL_LOGV) {
            Log.v(TAG, "PUSH Intent processed.");
        }
    }

    private static long findThreadId(Context context, GenericPdu pdu, int type) {
        String messageId;

        if (type == MESSAGE_TYPE_DELIVERY_IND) {
            messageId = new String(((DeliveryInd) pdu).getMessageId());
        } else {
            messageId = new String(((ReadOrigInd) pdu).getMessageId());
        }

        StringBuilder sb = new StringBuilder('(');
        sb.append(Telephony.Mms.MESSAGE_ID);
        sb.append('=');
        sb.append(DatabaseUtils.sqlEscapeString(messageId));
        sb.append(" AND ");
        sb.append(Telephony.Mms.MESSAGE_TYPE);
        sb.append('=');
        sb.append(PduHeaders.MESSAGE_TYPE_SEND_REQ);
        // TODO ContentResolver.query() appends closing ')' to the selection argument
        // sb.append(')');

        Cursor cursor = SqliteWrapper.query(context, context.getContentResolver(),
                Telephony.Mms.CONTENT_URI, new String[] { Telephony.Mms.THREAD_ID },
                sb.toString(), null, null);
        if (cursor != null) {
            try {
                if ((cursor.getCount() == 1) && cursor.moveToFirst()) {
                    long id = cursor.getLong(0);
                    cursor.close();
                    return id;
                }
            } finally {
                cursor.close();
            }
        }

        return -1;
    }

    private static boolean isDuplicateNotification(
            Context context, NotificationInd nInd) {
        byte[] rawLocation = nInd.getContentLocation();
        if (rawLocation != null) {
            String location = new String(rawLocation);
            String selection = Telephony.Mms.CONTENT_LOCATION + " = ?";
            String[] selectionArgs = new String[] { location };
            Cursor cursor = SqliteWrapper.query(
                    context, context.getContentResolver(),
                    Telephony.Mms.CONTENT_URI, new String[] { Telephony.Mms._ID },
                    selection, selectionArgs, null);
            if (cursor != null) {
                try {
                    if (cursor.getCount() > 0) {
                        // We already received the same notification before.
                        cursor.close();
                        //return true;
                    }
                } finally {
                    cursor.close();
                }
            }
        }
        return false;
    }
}
