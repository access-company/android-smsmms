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

import com.android.mms.logs.LogTag;

import android.content.Context;
import android.util.Config;
import com.klinker.android.logger.Log;

/**
 * Default retry scheme, based on specs.
 */
public class DefaultRetryScheme extends AbstractRetryScheme {
    private static final String TAG = LogTag.TAG;
    private static final boolean DEBUG = false;
    private static final boolean LOCAL_LOGV = DEBUG ? Config.LOGD : Config.LOGV;

    private static int[] sDefaultRetryScheme = {
        0, 1 * 60 * 1000, 5 * 60 * 1000, 10 * 60 * 1000, 30 * 60 * 1000};

    private synchronized static int[] getRetryScheme() {
        return sDefaultRetryScheme.clone();
    }

    public synchronized static void setRetryScheme(int[] scheme){
        sDefaultRetryScheme = scheme.clone();
    }

    private final int[] mRetryScheme = getRetryScheme();

    public DefaultRetryScheme(Context context, int retriedTimes) {
        super(retriedTimes);

        mRetriedTimes = mRetriedTimes < 0 ? 0 : mRetriedTimes;
        mRetriedTimes = mRetriedTimes >= mRetryScheme.length
                ? mRetryScheme.length - 1 : mRetriedTimes;

        // TODO Get retry scheme from preference.
    }

    @Override
    public int getRetryLimit() {
        return mRetryScheme.length;
    }

    @Override
    public long getWaitingInterval() {
        if (LOCAL_LOGV) {
            Log.v(TAG, "Next int: " + mRetryScheme[mRetriedTimes]);
        }
        return mRetryScheme[mRetriedTimes];
    }
}
