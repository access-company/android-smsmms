package com.android.mms.util;

import android.os.Build;
import android.os.Environment;
import android.os.StatFs;

public class StorageUtils {
    private static final long SAVE_NEW_MESSAGE_THRESHOLD = 2 * 1024 * 1024;
    private static final String sDirPath = Environment.getDataDirectory().getPath();

    public static long getStorageSize() {
        StatFs statFs = new StatFs(sDirPath);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            return statFs.getAvailableBlocksLong() * statFs.getBlockSizeLong();
        } else {
            return statFs.getAvailableBlocks() * statFs.getBlockSize();
        }
    }

    public static boolean canSaveNewMessage() {
        return getStorageSize() > SAVE_NEW_MESSAGE_THRESHOLD;
    }
}
